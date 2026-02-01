package sa.com.cloudsolutions.antikythera.parser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithArguments;
import com.github.javaparser.ast.type.Type;
import sa.com.cloudsolutions.antikythera.evaluator.AKBuddy;
import sa.com.cloudsolutions.antikythera.evaluator.EvaluatorFactory;
import sa.com.cloudsolutions.antikythera.evaluator.MethodInterceptor;
import sa.com.cloudsolutions.antikythera.evaluator.MockingEvaluator;
import sa.com.cloudsolutions.antikythera.evaluator.Reflect;
import sa.com.cloudsolutions.antikythera.generator.TypeWrapper;

import java.util.Optional;

/**
 * Wraps method call expressions to solve their argument types.
 * At the time that a method call is being evaluated, typically we only have the argument names
 * and not their types. findMethodDeclaration in AbstractCompiler requires that the types be known
 * this class bridges that gap.
 */
public class MCEWrapper {
    /**
     * The MCE being wrapped
     */
    private final NodeWithArguments<?> methodCallExpr;
    /**
     * The type of each argument (if correctly identified or else null)
     */
    private NodeList<Type> argumentTypes;

    private Callable matchingCallable;

    public MCEWrapper(NodeWithArguments<?> oce) {
        this.methodCallExpr = oce;
        argumentTypes = new NodeList<>();
    }

    /**
     *
     * @return the argument types maybe null if not properly identified
     */
    public NodeList<Type> getArgumentTypes() {
        return argumentTypes;
    }

    /**
     * Gets the argument types as an array of Classes.
     * Tries to resolve each type to a Class object.
     *
     * @return an array of Class objects representing the argument types, or null if argumentTypes is null
     */
    public Class<?>[] getArgumentTypesAsClasses()  {
        if (argumentTypes == null) {
            return null;
        }
        Class<?>[] classes = new Class<?>[argumentTypes.size()];

        for (int i = 0; i < argumentTypes.size(); i++) {

            Type type = argumentTypes.get(i);
            String elementType = type.getElementType().toString();
            try {
                classes[i] = Reflect.getComponentClass(elementType);
            } catch (ClassNotFoundException e) {
                argumentTypeAsNonPrimitiveClass(type, classes, i);
            }
        }

        return classes;
    }

    private void argumentTypeAsNonPrimitiveClass(Type type, Class<?>[] classes, int i) {
        if (methodCallExpr instanceof MethodCallExpr mce) {
            CompilationUnit cu = mce.findCompilationUnit().orElseThrow();
            TypeWrapper wrapper = AbstractCompiler.findType(cu, type);
            if (wrapper != null) {
                if (wrapper.getClazz() != null) {
                    classes[i] = wrapper.getClazz();
                }
                else {
                    MockingEvaluator eval = EvaluatorFactory.create(wrapper.getType().getFullyQualifiedName().orElseThrow(), MockingEvaluator.class);
                    try {
                        Class<?> cls = AKBuddy.createDynamicClass(new MethodInterceptor(eval));
                        classes[i] = cls;
                    } catch (ClassNotFoundException ex) {
                        classes[i] = Object.class;
                    }
                }
            }
        }
    }

    /**
     * Sets the types of the arguments for the method call expression
     * @param argumentTypes the types of the arguments
     */
    public void setArgumentTypes(NodeList<Type> argumentTypes) {
        this.argumentTypes = argumentTypes;
    }

    /**
     * Gets the underlying method call expression.
     *
     * @return the NodeWithArguments representing the method call
     */
    public NodeWithArguments<?> getMethodCallExpr() {
        return methodCallExpr;
    }

    /**
     * Returns the string representation of the method call expression.
     *
     * @return the string representation
     */
    @Override
    public String toString() {
        if (methodCallExpr != null) {
            return methodCallExpr.toString();
        }
        return "";
    }

    /**
     * Gets the name of the method being called.
     *
     * @return the method name, or null if it's not a MethodCallExpr
     */
    public String getMethodName() {
        if (methodCallExpr instanceof MethodCallExpr mce) {
            return mce.getNameAsString();
        }

        return null;
    }

    /**
     * Gets the callable declaration (method or constructor) that matches this call.
     *
     * @return the matching Callable
     */
    public Callable getMatchingCallable() {
        return matchingCallable;
    }

    /**
     * Sets the matching callable declaration.
     *
     * @param match the matching Callable
     */
    public void setMatchingCallable(Callable match) {
        this.matchingCallable = match;
    }

    /**
     * Returns the underlying expression as a MethodCallExpr if applicable.
     *
     * @return an Optional containing the MethodCallExpr, or empty if it's not one
     */
    public Optional<MethodCallExpr> asMethodCallExpr() {
        if (methodCallExpr instanceof MethodCallExpr mce) {
            return Optional.of(mce);
        }
        return Optional.empty();
    }
}
