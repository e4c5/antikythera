package sa.com.cloudsolutions.antikythera.depsolver;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.nodeTypes.NodeWithArguments;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.evaluator.Scope;
import sa.com.cloudsolutions.antikythera.evaluator.ScopeChain;
import sa.com.cloudsolutions.antikythera.exception.AntikytheraException;
import sa.com.cloudsolutions.antikythera.exception.DepsolverException;
import sa.com.cloudsolutions.antikythera.exception.GeneratorException;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.Callable;
import sa.com.cloudsolutions.antikythera.parser.ImportUtils;
import sa.com.cloudsolutions.antikythera.parser.ImportWrapper;
import sa.com.cloudsolutions.antikythera.parser.MCEWrapper;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

public class Resolver {

    private Resolver() {}

    /**
     * Resolve a field that is accessed through the <code>this.</code> prefix
     * @param node a graph node representing the current context
     * @param value the field access expression
     * @return a graph node representing the resolved field
     */
    public static GraphNode resolveThisFieldAccess(GraphNode node, FieldAccessExpr value) {
        TypeDeclaration<?> decl = node.getEnclosingType();
        if (decl != null) {

            FieldDeclaration f = decl.getFieldByName(value.getNameAsString()).orElse(null);
            if (f != null) {
                try {
                    node.addField(f);
                    Type t = f.getElementType();
                    String fqname = AbstractCompiler.findFullyQualifiedName(
                            node.getCompilationUnit(), t.asClassOrInterfaceType().getNameAsString()
                    );
                    if (fqname != null) {
                        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(fqname);
                        if (cu != null) {
                            TypeDeclaration<?> p = AbstractCompiler.getPublicType(cu);
                            if (p != null) {
                                return Graph.createGraphNode(p);
                            }
                        }
                    }
                } catch (AntikytheraException e) {
                    throw new GeneratorException(e);
                }
            }
        }
        return null;
    }

    /**
     * Resolve a field access expression
     * If the expression has a <code>this.</code> prefix, then the field is resolved within the
     * current class with help from the resolveThisField method
     * @param node represents a type
     * @param value a field access expression
     * @return a graph node representing the resolved field
     */
    public static GraphNode resolveField(GraphNode node, FieldAccessExpr value) {
        Expression scope = value.asFieldAccessExpr().getScope();
        if (scope.isNameExpr()) {

            ImportWrapper imp2 = AbstractCompiler.findImport(node.getCompilationUnit(),
                    scope.asNameExpr().getNameAsString()
            );
            if (imp2 != null) {
                node.getDestination().addImport(imp2.getImport());
                try {
                    if(imp2.getType() != null) {
                        Graph.createGraphNode(imp2.getType());
                    }
                    if(imp2.getField() != null) {
                        Graph.createGraphNode(imp2.getField());
                    }
                    else {
                        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(imp2.getNameAsString());
                        if (cu != null) {
                            AbstractCompiler.getMatchingType(cu, scope.asNameExpr().getNameAsString())
                                    .ifPresent(t -> createFieldNode(value, t));
                        }
                    }
                } catch (AntikytheraException e) {
                    throw new GeneratorException(e);
                }
            }
            else {
                return Resolver.resolveThisFieldAccess(node, value);
            }
        }
        else if (scope.isThisExpr()) {
            return  Resolver.resolveThisFieldAccess(node, value);
        }
        return null;
    }


    static void resolveArrayExpr(GraphNode node, Expression value) {
        ArrayInitializerExpr aie = value.asArrayInitializerExpr();
        for (Expression expr : aie.getValues()) {
            if (expr.isAnnotationExpr()) {
                AnnotationExpr anne = expr.asAnnotationExpr();
                String fqName = AbstractCompiler.findFullyQualifiedName(node.getCompilationUnit(), anne.getName().toString());
                if (fqName != null) {
                    node.getDestination().addImport(fqName);
                }
                if (anne.isNormalAnnotationExpr()) {
                    resolveNormalAnnotationExpr(node, anne.asNormalAnnotationExpr());
                }
            }
            else if(expr.isFieldAccessExpr()) {
                Resolver.resolveField(node, expr.asFieldAccessExpr());
            }
        }
    }


    static void resolveNormalAnnotationExpr(GraphNode node, NormalAnnotationExpr n) {
        ImportWrapper imp = AbstractCompiler.findImport(node.getCompilationUnit(), n.getNameAsString());
        if (imp != null) {
            node.getDestination().addImport(imp.getImport());
        }
        for(MemberValuePair pair : n.getPairs()) {
            Expression value = pair.getValue();
            if (value.isFieldAccessExpr()) {
                Resolver.resolveField(node, value.asFieldAccessExpr());
            }
            else if (value.isBinaryExpr()) {
                resolveBinaryExpr(node, value);
            }
            else if (value.isNameExpr()) {
                resolveNameExpression(node, value);
            }
            else if (value.isArrayInitializerExpr()) {
                Resolver.resolveArrayExpr(node, value);
            }
            else if (value.isClassExpr()) {
                ClassOrInterfaceType ct = value.asClassExpr().getType().asClassOrInterfaceType();
                ImportUtils.addImport(node, ct.getName().toString());
            }
        }
    }


    static void resolveBinaryExpr(GraphNode node, Expression value) {
        Expression left = value.asBinaryExpr().getLeft();
        if (left.isFieldAccessExpr()) {
            Resolver.resolveField(node, left.asFieldAccessExpr());
        }
        else if (left.isNameExpr()) {
            resolveNameExpression(node, left);
        }

        Expression right = value.asBinaryExpr().getRight();
        if (right.isFieldAccessExpr()) {
            Resolver.resolveField(node, right.asFieldAccessExpr());
        }
    }

    private static void resolveNameExpression(GraphNode node, Expression value)  {
        try {
            resolveNameExpr(node, value, new NodeList<>());
        } catch (AntikytheraException e) {
            throw new DepsolverException(e);
        }
    }

    public static Optional<Type> resolveScopedNameExpression(Expression scope, NodeWithSimpleName<?> fae,
                                                       GraphNode node, final Map<String, Type> names) throws AntikytheraException {
        if (names != null ) {
            Type t = names.get(scope.asNameExpr().getNameAsString());

            if (t != null) {
                return Optional.of(t);
            }
        }

        ImportWrapper imp = AbstractCompiler.findImport(node.getCompilationUnit(), scope.asNameExpr().getNameAsString());
        if (imp != null) {
            node.getDestination().addImport(imp.getImport());
            if (imp.isExternal()) {
                return getExternalType(fae, imp);
            }
            if (imp.getField() == null ) {
                if (imp.getImport().isAsterisk()) {
                    TypeDeclaration<?> td = imp.getType();
                    createFieldNode(fae, td);
                }
                else {
                    CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(imp.getImport().getNameAsString());
                    if (cu != null) {
                        TypeDeclaration<?> td = AbstractCompiler.getPublicType(cu);
                        createFieldNode(fae, td);
                    }
                }
            }
        }
        return Optional.empty();
    }


    static Optional<Type> getExternalType(NodeWithSimpleName<?> fae, ImportWrapper imp) {
        try {
            Class<?> c = Class.forName(imp.getNameAsString());
            Field f = c.getField(fae.getNameAsString());
            ClassOrInterfaceType ct = new ClassOrInterfaceType(null, f.getType().getTypeName());
            return Optional.of(ct);

        } catch (ReflectiveOperationException e) {
            System.err.println(e.getMessage());
        }
        return Optional.empty();
    }


    static void createFieldNode(NodeWithSimpleName<?> fae, TypeDeclaration<?> td)  {
        if (td != null) {
            td.getFieldByName(fae.getNameAsString()).ifPresent(Graph::createGraphNode);
        }
    }



    public static GraphNode resolveFieldAccess(GraphNode node, Expression expr, NodeList<Type> types) {
        final FieldAccessExpr fae = expr.asFieldAccessExpr();
        Expression scope = fae.getScope();

        if (scope.isNameExpr()) {
            return handleNameExprScope(node, fae, scope, types);
        } else if (scope.isFieldAccessExpr()) {
            return handleFieldAccessExprScope(node, fae, scope, types);
        } else {
            ImportWrapper imp = AbstractCompiler.findImport(node.getCompilationUnit(), fae.getNameAsString());
            if (imp != null) {
                node.getDestination().addImport(imp.getImport());
                if (imp.isExternal()) {
                    getExternalType(fae, imp).ifPresent(types::add);
                }
            }
            return null;
        }
    }

    private static GraphNode handleNameExprScope(GraphNode node, FieldAccessExpr fae, Expression scope, NodeList<Type> types) {
        Optional<Type> resolvedType = resolveScopedNameExpression(scope, fae, node, DepSolver.getNames());
        if (resolvedType.isPresent()) {
            Type t = resolvedType.get();
            ImportWrapper wrapper = AbstractCompiler.findImport(node.getCompilationUnit(), t.asString());
            if (wrapper != null && wrapper.getType() != null) {
                SimpleName name = fae.getName();
                Optional<FieldDeclaration> field = wrapper.getType().findFirst(FieldDeclaration.class, f -> f.getVariable(0).getNameAsString().equals(name.asString()));
                if (field.isPresent()) {
                    return Graph.createGraphNode(field.get());
                }
            }
            types.add(t);
        }
        return null;
    }

    private static GraphNode handleFieldAccessExprScope(GraphNode node, FieldAccessExpr fae, Expression scope, NodeList<Type> types) {
        GraphNode scopeNode = resolveFieldAccess(node, scope, types);
        if (scopeNode != null) {
            FieldDeclaration scopeField = ((FieldDeclaration) scopeNode.getNode()).asFieldDeclaration();
            ClassOrInterfaceType resolvedType = scopeField.getElementType().asClassOrInterfaceType();
            if (resolvedType != null) {
                String fqn = AbstractCompiler.findFullyQualifiedName(scopeNode.getCompilationUnit(), resolvedType.getName().asString());
                CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(fqn);
                if (cu != null) {
                    Optional<TypeDeclaration<?>> resolvedClass = AbstractCompiler.getMatchingType(cu, resolvedType.getName().asString());
                    if (resolvedClass.isPresent()) {
                        Optional<FieldDeclaration> field = resolvedClass.get().getFieldByName(fae.getNameAsString());
                        if (field.isPresent()) {
                            return Graph.createGraphNode(field.get());
                        }
                    }
                }
            }
        }
        return null;
    }

    static GraphNode chainedMethodCall(GraphNode node, MCEWrapper mceWrapper) throws AntikytheraException {
        if (mceWrapper.getMethodCallExpr() instanceof MethodCallExpr mce) {
            ScopeChain chain = ScopeChain.findScopeChain(mce);

            if (chain.isEmpty()) {
                copyMethod(mceWrapper, node);
                return node;
            } else {
                GraphNode gn = evaluateScopeChain(node, chain);
                if (gn != null) {
                    copyMethod(mceWrapper, gn);
                    return gn;
                }
            }
        }
        else if (mceWrapper.getMethodCallExpr() instanceof ObjectCreationExpr oce) {
            return ImportUtils.addImport(node, oce.getType());
        }
        return null;
    }

    static GraphNode evaluateScopeChain(GraphNode node, ScopeChain chain) throws AntikytheraException {
        GraphNode gn = node;
        Iterator<Scope> iterator = chain.getChain().reversed().iterator();
        while (iterator.hasNext() && gn != null) {
            Expression expr = iterator.next().getExpression();
            if (expr.isFieldAccessExpr()) {
                FieldAccessExpr fieldAccessExpr = expr.asFieldAccessExpr();
                gn = Resolver.resolveField(gn, fieldAccessExpr);
            }
            else if (expr.isMethodCallExpr()) {
                gn = copyMethod(resolveArgumentTypes(gn, expr.asMethodCallExpr()), gn);
            }
            else if (expr.isNameExpr()) {
                gn = evaluateNameExpr(expr, gn);
            }
        }

        return gn;
    }

    static GraphNode evaluateNameExpr(Expression expr, GraphNode gn) throws AntikytheraException {
        NameExpr nameExpr = expr.asNameExpr();
        TypeDeclaration<?> cdecl = gn.getEnclosingType();
        Type t = DepSolver.getNames().get(expr.toString());
        if (t == null) {
            Optional<FieldDeclaration> fd = cdecl.getFieldByName(nameExpr.getNameAsString());

            if (fd.isPresent()) {
                gn = findFieldNode(gn, fd.get());
            }
            else {
                gn = ImportUtils.addImport(gn, nameExpr.getName().toString());
            }
        }
        else {
            if (t.isClassOrInterfaceType()) {
                ClassOrInterfaceType ct = t.asClassOrInterfaceType();
                Optional<NodeList<Type>> types = ct.getTypeArguments();
                if (types.isPresent()) {
                    for (Type type : types.get()) {
                        GraphNode n = ImportUtils.addImport(gn, type);
                        if (n != null) {
                            gn = n;
                        }
                    }
                }
                else {
                    gn = ImportUtils.addImport(gn, t.asString());
                }
            }
            else {
                gn = ImportUtils.addImport(gn, t.asString());
            }
        }
        return gn;
    }

    private static GraphNode findFieldNode(GraphNode gn, FieldDeclaration fd) {
        Type field = fd.getElementType();
        gn.addField(fd);

        if (field != null) {
            for (AnnotationExpr ann : field.getAnnotations()) {
                ImportUtils.addImport(gn, ann.getNameAsString());
            }

            Type elementType = field.getElementType();
            if (elementType.isClassOrInterfaceType()) {
                Optional<NodeList<Type>> types = elementType.asClassOrInterfaceType().getTypeArguments();
                if (types.isPresent()) {
                    for (Type type : types.get()) {
                        ImportUtils.addImport(gn, type);
                    }
                }
                gn = ImportUtils.addImport(gn, elementType.asClassOrInterfaceType().getName().toString());
            } else {
                gn = ImportUtils.addImport(gn, elementType);
            }
        }
        return gn;
    }

    /**
     * Given a method call expression or new object creation expression, resolve the types of the arguments.
     * @param node a graph node representing the current context
     * @param mce method call expression or object creation expression
     * @return a Method Call Wrapper instance that contains the original method call as well as
     *              resolved argument types.
     *              If the arguments cannot be resolved correctly, the corresponding field
     *              in the MCEWrapper will be null.
     */
    public static MCEWrapper resolveArgumentTypes(GraphNode node, NodeWithArguments<?> mce)  {
        MCEWrapper mw = new MCEWrapper(mce);
        NodeList<Type> types = new NodeList<>();

        NodeList<Expression> arguments = mce.getArguments();

        for(Expression arg : arguments) {
            processExpression(node, arg, types);
        }
        if (types.size() == arguments.size()) {
            mw.setArgumentTypes(types);
        }

        return mw;
    }

    static void processExpression(GraphNode node, Expression expr, NodeList<Type> types)  {
        if (expr.isNameExpr()) {
            resolveNameExpr(node, expr, types);
        }
        else if (expr.isLiteralExpr()) {
            types.add(AbstractCompiler.convertLiteralToType(expr.asLiteralExpr()));
        }
        else if (expr.isFieldAccessExpr()) {
            Resolver.resolveFieldAccess(node, expr, types);
        }
        else if (expr.isMethodCallExpr()) {
            wrapCallable(node, expr.asMethodCallExpr(), types);
        }
        else if (expr.isObjectCreationExpr()) {
            wrapCallable(node, expr.asObjectCreationExpr(), types);
        }
        else if (expr.isMethodReferenceExpr()) {
            resolveMethodReference(node, expr);
        }
        else if (expr.isConditionalExpr()) {
            ConditionalExpr ce = expr.asConditionalExpr();
            if (ce.getThenExpr().isNameExpr()) {
                resolveNameExpr(node, ce.getThenExpr(), types);
            }
            if (ce.getElseExpr().isNameExpr()) {
                resolveNameExpr(node, ce.getElseExpr(), types);
            }
        }
        else if (expr.isArrayAccessExpr()) {
            ArrayAccessExpr aae = expr.asArrayAccessExpr();
            if (aae.getName().isNameExpr()) {
                resolveNameExpr(node, aae.getName(), types);
                types.getLast().ifPresent(t -> {
                    if (t.isArrayType()) {
                        Type at = types.removeLast();
                        types.add(at.asArrayType().getComponentType());
                    }
                });
            }
        } else if (expr.isClassExpr()) {
            ClassExpr ce = expr.asClassExpr();
            ImportUtils.addImport(node, ce.getType().asString());
        } else {
            // seems other types dont need special handling they are caught else where
        }
    }

    static void resolveMethodReference(GraphNode node, Expression arg) {

        MethodReferenceExpr mre = arg.asMethodReferenceExpr();
        Expression scope = mre.getScope();
        if (scope.isNameExpr()) {
            ImportUtils.addImport(node, scope.asNameExpr().getNameAsString());
        }
        else if (scope.isThisExpr()) {
            for (MethodDeclaration m : node.getEnclosingType().getMethodsByName(mre.getIdentifier())) {
                Graph.createGraphNode(m);
            }
        }
        else if (scope.isTypeExpr()) {
            // Resolve the type expression to its corresponding class
            String typeName = scope.asTypeExpr().getType().asString();
            String fullQulifiedName=AbstractCompiler.findFullyQualifiedName(node.getCompilationUnit(), typeName);
            CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(fullQulifiedName);
            if (cu != null) {
                TypeDeclaration<?> typeDecl = AbstractCompiler.getPublicType(cu);
                if (typeDecl != null) {
                    // Find the method in the resolved class
                    for (MethodDeclaration m : typeDecl.getMethodsByName(mre.getIdentifier())) {
                        Graph.createGraphNode(m);
                    }
                }
            }
            ImportUtils.addImport(node, typeName);
        }
    }

    static void wrapCallable(GraphNode node, NodeWithArguments<?> arg, NodeList<Type> types) {
        if (arg instanceof MethodCallExpr argMethodCall) {
            MCEWrapper wrap = resolveArgumentTypes(node, arg);
            GraphNode gn = Resolver.chainedMethodCall(node, wrap);
            if (gn != null) {
                if (gn.getNode() instanceof MethodDeclaration md) {
                    Type t = md.getType();
                    ImportUtils.addImport(node, t);
                    types.add(t);
                } else if (gn.getNode() instanceof ClassOrInterfaceDeclaration cid) {
                    Optional<Callable> omd = AbstractCompiler.findCallableDeclaration(wrap, cid);
                    if (omd.isPresent()) {
                        Callable cd = omd.get();
                        if (cd.isCallableDeclaration()) {
                            if (cd.isMethodDeclaration()) {
                                Type t = cd.asMethodDeclaration().getType();
                                types.add(t);
                                ImportUtils.addImport(node, t);

                            }
                            cd.getCallableDeclaration().findAncestor(ClassOrInterfaceDeclaration.class).ifPresent(c ->
                                ImportUtils.addImport(node, c.getNameAsString())
                            );
                        }
                    } else {
                        Type t = lombokSolver(argMethodCall, cid, gn);
                        if (t != null) {
                            types.add(t);
                        }
                    }
                }
            }
        }
    }

    static Type lombokSolver(MethodCallExpr argMethodCall, ClassOrInterfaceDeclaration cid, GraphNode gn) {
        if (argMethodCall.getNameAsString().startsWith("get") &&
                cid.getAnnotationByName("Data").isPresent() ||
                cid.getAnnotationByName("Getter").isPresent()
        ) {
            String field = argMethodCall.getNameAsString().substring(3);
            if (!field.isEmpty()) {
                Optional<FieldDeclaration> fd = cid.getFieldByName(ClassProcessor.classToInstanceName(field));
                if (fd.isPresent()) {
                    Type t = fd.get().getElementType();
                    ImportUtils.addImport(gn, t);

                    return t;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static GraphNode copyMethod(MCEWrapper mceWrapper, GraphNode node) {
        TypeDeclaration<?> cdecl = node.getEnclosingType();
        if (cdecl != null) {
            Optional<Callable> md = AbstractCompiler.findCallableDeclaration(
                    mceWrapper, cdecl
            );

            if (md.isPresent() && md.get().isMethodDeclaration()) {
                MethodDeclaration method = md.get().asMethodDeclaration();
                for (Type ex : method.getThrownExceptions()) {
                    ImportUtils.addImport(node, ex.asString());
                }

                if (method.isAbstract()) {
                    Optional<ClassOrInterfaceDeclaration> parent = method.findAncestor(ClassOrInterfaceDeclaration.class);

                    if (!parent.get().isInterface()) {
                        AbstractCompiler.findMethodDeclaration(mceWrapper, cdecl, false)
                                .ifPresent(overRides -> Graph.createGraphNode(overRides.getCallableDeclaration()));
                    }
                }
                return Graph.createGraphNode(method);
            } else if (mceWrapper.getMethodCallExpr() instanceof MethodCallExpr mce && cdecl instanceof ClassOrInterfaceDeclaration decl) {
                Type t = lombokSolver(mce, decl, node);
                if (t != null && t.isClassOrInterfaceType()) {
                    return ImportUtils.addImport(node, t.asClassOrInterfaceType().getNameAsString());
                } else {
                    ImportWrapper imp = AbstractCompiler.findImport(node.getCompilationUnit(), mce.getNameAsString());
                    if (imp != null) {
                        node.getDestination().addImport(imp.getImport());
                        if (imp.getMethodDeclaration() != null) {
                            Graph.createGraphNode(imp.getMethodDeclaration());
                        }
                    }
                    else {
                        // desperate measure hack
                        // todo remove this
                        for (MethodDeclaration method : decl.getMethodsByName(mce.getNameAsString())) {
                            if (method.getParameters().size() == mce.getArguments().size()) {
                                Graph.createGraphNode(method);
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    static void resolveNameExpr(GraphNode node, Expression arg, NodeList<Type> types) {
        Type t = DepSolver.getNames().get(arg.asNameExpr().getNameAsString());
        if (t != null) {
            types.add(t);
        }
        else {

            Optional<FieldDeclaration> fd = node.getEnclosingType().getFieldByName(arg.asNameExpr().getNameAsString());
            if (fd.isPresent()) {
                node.addField(fd.get());
                Type field = fd.get().getElementType();

                if (field != null) {
                    types.add(field);
                    ImportUtils.addImport(node, field.getElementType().asString());
                    for (AnnotationExpr ann : field.getAnnotations()) {
                        ImportUtils.addImport(node, ann.getNameAsString());
                    }
                }
            }
            else {
                ImportUtils.addImport(node, arg.asNameExpr().getNameAsString());
            }
        }
    }

    public static void resolveReturnType(ReturnStmt returnStmt, MethodDeclaration md) {
        TypeDeclaration<?> from = md.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
        Expression expression = returnStmt.getExpression().orElse(null);
        if (expression != null && from != null && expression.isObjectCreationExpr()) {
            ObjectCreationExpr objectCreationExpr = expression.asObjectCreationExpr();
            if (objectCreationExpr.getType().asString().contains("ResponseEntity")) {
                for (Type typeArg : objectCreationExpr.getType().getTypeArguments().orElse(new NodeList<>())) {
                    // todo finish this off
                    //solveTypeDependencies(from, typeArg);
                }
            }
        }
    }
}
