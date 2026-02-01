package sa.com.cloudsolutions.antikythera.parser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import sa.com.cloudsolutions.antikythera.depsolver.DepSolver;
import sa.com.cloudsolutions.antikythera.depsolver.Graph;
import sa.com.cloudsolutions.antikythera.evaluator.ArgumentGenerator;
import sa.com.cloudsolutions.antikythera.evaluator.NullArgumentGenerator;
import sa.com.cloudsolutions.antikythera.evaluator.SpringEvaluator;

import java.io.IOException;

/**
 * Abstract base class for parsers that solve dependencies.
 */
public abstract class DepsolvingParser {
    CompilationUnit cu;
    protected SpringEvaluator evaluator;

    /**
     * Starts the parsing process for all methods in the compilation unit.
     *
     * @throws IOException if there is an error during processing
     */
    public void start() throws IOException {
        for(TypeDeclaration<?> decl : cu.getTypes()) {
            DepSolver solver = DepSolver.createSolver();
            decl.findAll(MethodDeclaration.class).forEach(md -> {
                if (!md.isPrivate()) {
                    Graph.createGraphNode(md);

                }
            });
            solver.dfs();
        }
    }

    /**
     * Starts the parsing process for a specific method.
     *
     * @param method the name of the method to process
     * @throws IOException if there is an error during processing
     */
    public void start(String method) throws IOException{
        for(TypeDeclaration<?> decl : cu.getTypes()) {
            DepSolver solver = DepSolver.createSolver();
            decl.findAll(MethodDeclaration.class).forEach(md -> {
                if (!md.isPrivate() && md.getNameAsString().equals(method)) {
                    Graph.createGraphNode(md);
                }
            });
            solver.dfs();
        }

        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodDeclaration md, Void arg) {
                /*
                 * I would gladly do this without a visitor, but discovered a bug in findAll()
                 */
                if (md.getNameAsString().equals(method)) {
                    evaluateMethod(md, new NullArgumentGenerator());
                }
                super.visit(md, arg);
            }
        }, null);

    }


    /**
     * Evaluates a method to generate tests or perform analysis.
     *
     * @param md the method declaration
     * @param gen the argument generator
     */
    public abstract void evaluateMethod(MethodDeclaration md, ArgumentGenerator gen);

}
