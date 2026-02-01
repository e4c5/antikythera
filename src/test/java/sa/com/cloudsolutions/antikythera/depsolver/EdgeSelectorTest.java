package sa.com.cloudsolutions.antikythera.depsolver;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EdgeSelectorTest {

    @Test
    void testSimpleCycle() {
        // A -> B -> A
        BeanDependency aToB = new BeanDependency("A", "B", InjectionType.FIELD, null, "b");
        BeanDependency bToA = new BeanDependency("B", "A", InjectionType.FIELD, null, "a");

        Map<String, Set<BeanDependency>> dependencies = new HashMap<>();
        dependencies.put("A", Set.of(aToB));
        dependencies.put("B", Set.of(bToA));

        EdgeSelector selector = new EdgeSelector(dependencies);
        List<List<String>> cycles = List.of(List.of("A", "B"));

        Set<BeanDependency> cuts = selector.selectEdgesToCut(cycles);

        // Should cut one edge
        assertEquals(1, cuts.size());
    }

    @Test
    void testWeighting() {
        // A -> B (Constructor, wt 3) -> A
        // A -> B (Field, wt 1) -> A  (Parallel edge? No, assume graph allows multiple edges or just consider the path)

        // Let's use a 3-node cycle where one edge is cheap
        // A -> B (Constructor)
        // B -> C (Constructor)
        // C -> A (Field)

        BeanDependency aToB = new BeanDependency("A", "B", InjectionType.CONSTRUCTOR, null, "b");
        BeanDependency bToC = new BeanDependency("B", "C", InjectionType.CONSTRUCTOR, null, "c");
        BeanDependency cToA = new BeanDependency("C", "A", InjectionType.FIELD, null, "a");

        Map<String, Set<BeanDependency>> dependencies = new HashMap<>();
        dependencies.put("A", Set.of(aToB));
        dependencies.put("B", Set.of(bToC));
        dependencies.put("C", Set.of(cToA));

        EdgeSelector selector = new EdgeSelector(dependencies);
        List<List<String>> cycles = List.of(List.of("A", "B", "C"));

        Set<BeanDependency> cuts = selector.selectEdgesToCut(cycles);

        assertEquals(1, cuts.size());
        // Should cut the cheapest edge (Field)
        assertTrue(cuts.contains(cToA), "Should have cut the FIELD injection edge");
    }

    @Test
    void testGreedySuboptimal() {
        // Cycles:
        // C1: X -> W -> Y -> X
        // C2: X -> W -> Z -> X

        // Edges:
        // X->W: CONSTRUCTOR (3.0). Shared.
        // W->Y: FIELD (1.0). Unique to C1.
        // Y->X: BEAN_METHOD (4.0). Unique to C1.
        // W->Z: BEAN_METHOD (4.0). Unique to C2.
        // Z->X: BEAN_METHOD (4.0). Unique to C2.

        BeanDependency xw = new BeanDependency("X", "W", InjectionType.CONSTRUCTOR, null, "w");
        BeanDependency wy = new BeanDependency("W", "Y", InjectionType.FIELD, null, "y");
        BeanDependency yx = new BeanDependency("Y", "X", InjectionType.BEAN_METHOD, null, "x");
        BeanDependency wz = new BeanDependency("W", "Z", InjectionType.BEAN_METHOD, null, "z");
        BeanDependency zx = new BeanDependency("Z", "X", InjectionType.BEAN_METHOD, null, "x");

        Map<String, Set<BeanDependency>> dependencies = new HashMap<>();
        dependencies.put("X", Set.of(xw));
        dependencies.put("W", Set.of(wy, wz));
        dependencies.put("Y", Set.of(yx));
        dependencies.put("Z", Set.of(zx));

        EdgeSelector selector = new EdgeSelector(dependencies);

        List<List<String>> cycles = new ArrayList<>();
        cycles.add(List.of("X", "W", "Y")); // C1
        cycles.add(List.of("X", "W", "Z")); // C2

        // Manual check of weights (including in-degree penalties)
        // X->W: 3.0 + 0.5 * 1 (from X) = 3.5
        // W->Y: 1.0 + 0.5 * 1 (from W) = 1.5
        assertEquals(3.5, selector.computeWeight(xw), 0.1);
        assertEquals(1.5, selector.computeWeight(wy), 0.1);

        Set<BeanDependency> cuts = selector.selectEdgesToCut(cycles);

        double totalWeight = cuts.stream().mapToDouble(selector::computeWeight).sum();

        System.out.println("Cuts made: " + cuts);
        System.out.println("Total weight: " + totalWeight);

        // Optimal solution is cutting X->W (weight 3.5)
        // Greedy solution:
        // 1. Picks W->Y (weight 1.5, covers 1 cycle). Ratio 1/1.5 = 0.66
        //    (vs X->W ratio 2/3.5 = 0.57)
        //    (vs W->Z ratio 1/4.5 = 0.22)
        // 2. Remaining cycle C2. Picks X->W (weight 3.5). Ratio 1/3.5 = 0.28.
        // Total greedy weight: 1.5 + 3.5 = 5.0.

        boolean isOptimal = Math.abs(totalWeight - 3.5) < 0.001;
        assertFalse(isOptimal, "Greedy algorithm found the optimal solution, which was unexpected for this graph!");

        // Explicitly check it found the suboptimal solution
        assertEquals(5.0, totalWeight, 0.001, "Expected greedy algorithm to produce total weight of 5.0");
    }
}
