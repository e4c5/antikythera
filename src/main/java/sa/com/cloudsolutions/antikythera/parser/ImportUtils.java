package sa.com.cloudsolutions.antikythera.parser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.type.Type;
import sa.com.cloudsolutions.antikythera.depsolver.Graph;
import sa.com.cloudsolutions.antikythera.depsolver.GraphNode;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;

import java.util.Optional;

/**
 * Utility class for handling imports and resolving types to graph nodes.
 */
public class ImportUtils {
    private ImportUtils() {}

    /**
     * Adds an import to the graph node based on an expression.
     * Supports NameExpr and ObjectCreationExpr.
     *
     * @param node the current graph node
     * @param expr the expression to derive the import from
     * @return the resolved GraphNode, or null if not found
     */
    public static GraphNode addImport(GraphNode node, Expression expr) {
        if (expr.isNameExpr()) {
            return ImportUtils.addImport(node, expr.asNameExpr().getNameAsString());
        }
        else if (expr.isObjectCreationExpr()) {
            return ImportUtils.addImport(node, expr.asObjectCreationExpr().getType());
        }
        return null;
    }
         * Resolves the type using AbstractCompiler and adds an import to the destination
         * compilation unit if needed.
         *
         * When the type resolves to a source TypeDeclaration, a GraphNode is created and
         * returned; when resolution only succeeds via reflection or fails entirely, no
         * GraphNode is created.
         *
         * @param node the current graph node
         * @param type the Type to resolve
         * @return the GraphNode for a resolved AST-based type declaration, or null when
         *         no such type node is created (including reflection-only resolutions)
     */
    public static GraphNode addImport(GraphNode node, Type type) {
        CompilationUnit compilationUnit = node.getCompilationUnit();
        TypeWrapper wrapper = AbstractCompiler.findType(compilationUnit, type);
        if (wrapper != null) {
            String packageName = compilationUnit.getPackageDeclaration().isPresent()
                    ? compilationUnit.getPackageDeclaration().orElseThrow().getNameAsString() : "";

            if (wrapper.getType() != null) {
                return getGraphNodeForImport(node, wrapper, packageName, compilationUnit);
            }
            else {
                String importFrom = findPackage(wrapper.getClazz());
                if (!importFrom.equals(packageName)
                        && !importFrom.equals("java.lang")
                        && !packageName.isEmpty()) {
                    node.getDestination().addImport(wrapper.getClazz().getName());
                }
            }
        }

        return null;
    }

    private static GraphNode getGraphNodeForImport(GraphNode node, TypeWrapper wrapper, String packageName, CompilationUnit compilationUnit) {
        GraphNode n = Graph.createGraphNode(wrapper.getType());
        if (!packageName.equals(
                findPackage(wrapper.getType())) && !packageName.isEmpty()) {
            // Check if typeDeclaration is null before accessing it
            TypeDeclaration<?> typeDecl = n.getTypeDeclaration();
            if (typeDecl != null) {
                node.getDestination().addImport(typeDecl.getFullyQualifiedName().orElseThrow());
            } else {
                // Fallback: use the wrapper's fully qualified name or find it from the type
                String fqn = wrapper.getFullyQualifiedName();
                if (fqn == null) {
                    fqn = AbstractCompiler.findFullyQualifiedName(compilationUnit, wrapper.getType().getNameAsString());
                }
                if (fqn != null && !fqn.equals(packageName)) {
                    node.getDestination().addImport(fqn);
                }
            }
        }
        return n;
    }

    /**
     * Adds an import to the graph node based on a string name.
     * Tries to find an existing import or resolve the fully qualified name and may
     * create additional graph nodes for referenced types, fields, or enum constants.
     *
     * @param node the current graph node
     * @param name the name to resolve (e.g., class name)
     * @return a GraphNode for a resolved type when one is created, or null when no
     *         type node is created even if imports or non-type graph nodes are added
     */
    public static GraphNode addImport(GraphNode node, String name) {
        GraphNode returnValue = null;
        ImportWrapper imp = AbstractCompiler.findImport(node.getCompilationUnit(), name);
        if (imp != null) {
            node.getDestination().addImport(imp.getImport());
            if (imp.getType() != null) {
                returnValue = Graph.createGraphNode(imp.getType());
            }
            if (imp.getField() != null) {
                Graph.createGraphNode(imp.getField());
            } else if (imp.getImport().isAsterisk() && !imp.isExternal()) {
                CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(imp.getImport().getNameAsString());
                if (cu != null) {
                    AbstractCompiler.getMatchingType(cu, name).ifPresent(Graph::createGraphNode);
                }
            }
        } else {
            String fullyQualifiedName = AbstractCompiler.findFullyQualifiedName(node.getCompilationUnit(), name);
            Optional<TypeDeclaration<?>> matching = AntikytheraRunTime.getTypeDeclaration(fullyQualifiedName);
            if (matching.isPresent()) {
                return (Graph.createGraphNode(matching.get()));
            }
        }
        return returnValue;
    }

    /**
     * Finds the package name for a given class.
     *
     * @param clazz the class
     * @return the package name, or empty string if not in a package
     */
    public static String findPackage(Class<?> clazz) {
        if (clazz.getPackage() != null) {
            return clazz.getPackage().getName();
        }
        return "";
    }

    /**
     * Finds the package name for a given TypeDeclaration.
     *
     * @param t the TypeDeclaration
     * @return the package name, or empty string if not found
     */
    public static String findPackage(TypeDeclaration<?> t) {
        return t.findCompilationUnit()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(NodeWithName::getNameAsString)
                .orElse("");
    }
}
