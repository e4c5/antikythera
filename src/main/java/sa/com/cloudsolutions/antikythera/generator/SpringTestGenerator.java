package sa.com.cloudsolutions.antikythera.generator;


import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.http.ResponseEntity;
import sa.com.cloudsolutions.antikythera.evaluator.Variable;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VoidType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.parser.ImportWrapper;
import sa.com.cloudsolutions.antikythera.parser.RestControllerParser;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test generator for spring applications.
 *
 * We traverse the code in the method line by line until we encounter an if/else statement. At that
 * point we generate a truth table to determine what values will lead to the condition being true.
 *
 * The first eligible candidate set of values will be chosen for the true state which will result
 * in the THEN branch being executed. We proceed until a return statement is encountered. At that
 * point we generate the tests based on the values that were applied to the condition. These values
 * may have percolated downward from method arguments. So we need to trace the values back to the
 * arguments, locals or fields to set them appropriately.
 *
 * If further branching is encountered the strategy will be repeated.
 *
 * When we encounter an IF statement, it's state will change to GREY, we will then traverse the
 * THEN branch until we reach a return statement. At that point we have to start all over again
 * and take the ELSE branch (if it exists).
 *
 * That means a function will have to be executed multiple times until we have covered all
 * possible branches. Therefor we need to keep track of the last line that has been executed.
 *
 * There is a Caveat. Simple controller functions without any branching in them also exists and
 * actions taken depend sorely on the query string or post body. Therefore, it's always necessary
 * for us to try methods without branching three times. The first time without any query strings,
 * Secondly with naive values and finally with values that will result in queries being executed.
 */
public class SpringTestGenerator extends  TestGenerator {
    private static final Logger logger = LoggerFactory.getLogger(SpringTestGenerator.class);
    /**
     * The URL path component common to all functions in a controller.
     */
    private String commonPath;
    /**
     * The names have have already been assigned to various tests.
     * Because of overloaded methods and the need to write multiple tests for a single end point
     * we may end up with duplicate method names. To avoid that we add a suffix of the query
     * string arguments to distinguish overloaded and a alphabetic suffix to identify multiple
     * tests for the same method.
     */
    Set<String> testMethodNames = new HashSet<>();

    /**
     * The compilation unit that represents the tests being generated.
     * We use the nodes of a Java Parser AST to build up the class rather than relying on strings
     *
     */
    CompilationUnit gen = new CompilationUnit();

    /**
     * The preconditions that need to be met before the test can be executed.
     */
    private List<Expression> preConditions;

    /**
     * Boolean value indicating if the method under test has any branching.
     */
    private boolean branched;

    /**
     * We are going to try to generate tests assuming no query strings or post data
     */
    private final int NULL_STATE = 0;
    /**
     * Tests will be written providing not null values for query strings or post data.
     */
    private final int DUMMY_STATE = 1;
    /**
     * Tests will be written with values that were identified from database queries.
     */
    private final int ENLIGHTENED_STATE = 2;
    /**
     * The current state of the test generation. It will be one of the values above.
     */
    private int state = NULL_STATE;
    /**
     * Create tests based on the method declaration and return type
     * @param md the descriptor of the method for which we are about to write tests.
     * @param controllerResponse the ControllerResponse instance, inside that we will find the
     *                           ResponseEntity as well as the body of the ResponseEntity.
     */
    @Override
    public void createTests(MethodDeclaration md, ControllerResponse controllerResponse) {
        RestControllerParser.getStats().setTests(RestControllerParser.getStats().getTests() + 1);
        for (AnnotationExpr annotation : md.getAnnotations()) {
            if (annotation.getNameAsString().equals("GetMapping") ) {
                buildGetMethodTests(md, annotation, controllerResponse);
            }
            else if(annotation.getNameAsString().equals("PostMapping")) {
                buildPostMethodTests(md, annotation, controllerResponse);
            }
            else if(annotation.getNameAsString().equals("DeleteMapping")) {
                buildDeleteMethodTests(md, annotation, controllerResponse);
            }
            else if(annotation.getNameAsString().equals("RequestMapping") && annotation.isNormalAnnotationExpr()) {
                NormalAnnotationExpr normalAnnotation = annotation.asNormalAnnotationExpr();
                for (var pair : normalAnnotation.getPairs()) {
                    if (pair.getNameAsString().equals("method")) {
                        if (pair.getValue().toString().equals("RequestMethod.GET")) {
                            buildGetMethodTests(md, annotation, controllerResponse);
                        }
                        if (pair.getValue().toString().equals("RequestMethod.POST")) {
                            buildPostMethodTests(md, annotation, controllerResponse);
                        }
                        if (pair.getValue().toString().equals("RequestMethod.PUT")) {
                            buildPutMethodTests(md, annotation, controllerResponse);
                        }
                        if (pair.getValue().toString().equals("RequestMethod.DELETE")) {
                            buildDeleteMethodTests(md, annotation, controllerResponse);
                        }
                    }
                }
            }
        }
    }

    private void buildDeleteMethodTests(MethodDeclaration md, AnnotationExpr annotation, ControllerResponse response) {
        httpWithoutBody(md, annotation, "makeDelete", response);
    }

    private void buildPutMethodTests(MethodDeclaration md, AnnotationExpr annotation, ControllerResponse returnType) {
        httpWithBody(md, annotation, returnType, "makePut");
    }

    private void buildGetMethodTests(MethodDeclaration md, AnnotationExpr annotation, ControllerResponse returnType) {
        httpWithoutBody(md, annotation, "makeGet", returnType);
    }

    private void httpWithoutBody(MethodDeclaration md, AnnotationExpr annotation, String call, ControllerResponse response)  {
        final MethodDeclaration testMethod = buildTestMethod(md);
        MethodCallExpr makeGetCall = new MethodCallExpr(call);
        makeGetCall.addArgument(new NameExpr("headers"));
        BlockStmt body = getBody(testMethod);

        if(md.getParameters().isEmpty()) {
            /*
             * Empty parameters are very easy.
             */
            makeGetCall.addArgument(new StringLiteralExpr(commonPath.replace("\"", "")));
        }
        else {
            /*
             * Non-empty parameters.
             */
            ControllerRequest request = new ControllerRequest();
            handleURIVariables(md, request);

            request.setPath(getPath(annotation).replace("\"", ""));

            addQueryParams(makeGetCall, request, body);
        }
        VariableDeclarationExpr responseVar = new VariableDeclarationExpr(new ClassOrInterfaceType(null, "Response"), "response");
        AssignExpr assignExpr = new AssignExpr(responseVar, makeGetCall, AssignExpr.Operator.ASSIGN);

        body.addStatement(new ExpressionStmt(assignExpr));

        addCheckStatus(md, testMethod, response);
        gen.getType(0).addMember(testMethod);

    }

    private  void addQueryParams(MethodCallExpr getCall, ControllerRequest request, BlockStmt body) {
        getCall.addArgument(new StringLiteralExpr(request.getPath()));
        if(!request.getQueryParameters().isEmpty()) {
            body.addStatement("Map<String, String> queryParams = new HashMap<>();");
            for(Map.Entry<String, String> entry : request.getQueryParameters().entrySet()) {
                if (argumentGenerator != null) {
                    Variable v = argumentGenerator.getArguments().get(entry.getKey());
                    if (v != null) {
                        body.addStatement(String.format("queryParams.put(\"%s\", \"%s\");",
                                entry.getKey(), v.getValue()));
                    }
                    else {
                        logger.warn("Could not get value for {}", entry.getValue());
                    }
                }
            }
            getCall.addArgument(new NameExpr("queryParams"));
        }
    }

    private void buildPostMethodTests(MethodDeclaration md, AnnotationExpr annotation, ControllerResponse returnType) {
        httpWithBody(md, annotation, returnType, "makePost");
    }

    private Type getReturnType(MethodDeclaration md, ControllerResponse resp) {
        if (resp.getType() == null
                || resp.getType().toString().equals("ResponseEntity")
                || resp.getType().toString().equals("Object")) {
            return md.getType();
        }
        return resp.getType();
    }

    private BlockStmt getBody(MethodDeclaration md) {
        return md.getBody().orElseGet(() -> {
            BlockStmt blockStmt = new BlockStmt();
            md.setBody(blockStmt);
            return blockStmt;
        });
    }

    private void addCheckStatus(MethodDeclaration mut, MethodDeclaration md, ControllerResponse resp) {

        Type returnType = getReturnType(mut, resp);

        BlockStmt body = getBody(md);

        if (resp.getBody() != null) {
            if (resp.getBody().getValue() != null && returnType.isClassOrInterfaceType()) {
                Type respType;
                if (returnType.asClassOrInterfaceType().getTypeArguments().isPresent()) {
                    respType = new ClassOrInterfaceType(null, returnType.asClassOrInterfaceType().getTypeArguments().get().get(0).toString());
                } else {
                    respType = new ClassOrInterfaceType(null, returnType.asClassOrInterfaceType().getNameAsString());
                }
                ImportWrapper wrapper = AbstractCompiler.findImport(mut.findCompilationUnit().get(), respType.toString());
                if (wrapper != null) {
                    gen.addImport(wrapper.getImport());
                }
                body.addStatement(createResponseObject(respType));
                addHttpStatusCheck(body, resp.getStatusCode());

                MethodCallExpr as = new MethodCallExpr(new NameExpr("Assert"), "assertNotNull");
                as.addArgument("resp");
                body.addStatement(new ExpressionStmt(as));

            } else {
                MethodCallExpr as = new MethodCallExpr(new NameExpr("Assert"), "assertTrue");
                as.addArgument("response.getBody().asString().isEmpty()");
                body.addStatement(new ExpressionStmt(as));
                addHttpStatusCheck(body, resp.getStatusCode());
            }
        }

    }

    private static ExpressionStmt createResponseObject(Type respType) {
        VariableDeclarator variableDeclarator = new VariableDeclarator(respType, "resp");
        MethodCallExpr methodCallExpr = new MethodCallExpr(new NameExpr("response"), "as");
        methodCallExpr.addArgument(respType.toString() + ".class");
        variableDeclarator.setInitializer(methodCallExpr);
        VariableDeclarationExpr variableDeclarationExpr = new VariableDeclarationExpr(variableDeclarator);
        return new ExpressionStmt(variableDeclarationExpr);
    }


    private void httpWithBody(MethodDeclaration md, AnnotationExpr annotation, ControllerResponse resp, String call) {

        MethodDeclaration testMethod = buildTestMethod(md);
        MethodCallExpr makePost = new MethodCallExpr(call);
        BlockStmt body = getBody(testMethod);

        ControllerRequest request = new ControllerRequest();
        request.setPath(getPath(annotation).replace("\"", ""));
        handleURIVariables(md, request);

        if(md.getParameters().isNonEmpty()) {
            Parameter requestBody = findRequestBody(md);
            if(requestBody != null) {
                String paramClassName = requestBody.getTypeAsString();

                if (requestBody.getType().isClassOrInterfaceType()) {
                    setupRequestBody(requestBody, paramClassName, testMethod, body, makePost);
                }
            }
            else {
                makePost.addArgument(new StringLiteralExpr(""));
                logger.warn("No RequestBody found for {}", md.getName());
            }
        }

        prepareRequest(makePost, request, body);

        gen.getType(0).addMember(testMethod);

        VariableDeclarationExpr responseVar = new VariableDeclarationExpr(new ClassOrInterfaceType(null, "Response"), "response");
        AssignExpr assignExpr = new AssignExpr(responseVar, makePost, AssignExpr.Operator.ASSIGN);
        body.addStatement(new ExpressionStmt(assignExpr));


        addHttpStatusCheck(body, resp.getStatusCode());
        Type returnType = resp.getType();
        if (returnType != null) {
            /*
             * There maybe controllers that do not return a body. In that case the
             * return type will be null. But we are not bothering with them for now.
             */
            if (returnType.isClassOrInterfaceType() && returnType.asClassOrInterfaceType().getTypeArguments().isPresent()) {
                NodeList<Type> a = returnType.asClassOrInterfaceType().getTypeArguments().get();
                if (!a.isEmpty() && a.getFirst().isPresent() && a.getFirst().get().toString().equals("String")) {
                    testForResponseBodyAsString(md, resp, body);
                }
                else {
                    logger.warn("THIS testing path is not completed");
                }
            } else {
                List<String> IncompatibleReturnTypes = List.of("void", "CompletableFuture", "?");
                if (! IncompatibleReturnTypes.contains(returnType.toString()))
                {
                    Type respType = new ClassOrInterfaceType(null, returnType.asClassOrInterfaceType().getNameAsString());
                    if (respType.toString().equals("String")) {
                        testForResponseBodyAsString(md, resp, body);
                    } else {
                        addCheckStatus(md, testMethod, resp);
                    }
                }
            }
        }
    }

    private static void testForResponseBodyAsString(MethodDeclaration md, ControllerResponse resp, BlockStmt body) {
        body.addStatement("String resp = response.getBody().asString();");
        Object response = resp.getResponse();
        if(response instanceof ResponseEntity<?> re) {
            body.addStatement(String.format("Assert.assertEquals(resp,\"%s\");", re.getBody()));
        }
        else {
            body.addStatement("Assert.assertNotNull(resp);");
            logger.warn("Reponse body is empty for {}", md.getName());
        }
    }

    private void setupRequestBody(Parameter requestBody, String paramClassName, MethodDeclaration testMethod, BlockStmt body, MethodCallExpr makePost) {
        var cdecl = requestBody.getType().asClassOrInterfaceType();
        switch (cdecl.getNameAsString()) {
            case "List": {
                prepareBody("java.util.List", new ClassOrInterfaceType(null, paramClassName), "List.of", testMethod);
                break;
            }

            case "Set": {
                prepareBody("java.util.Set", new ClassOrInterfaceType(null, paramClassName), "Set.of", testMethod);
                break;
            }

            case "Map": {
                prepareBody("java.util.Map", new ClassOrInterfaceType(null, paramClassName), "Map.of", testMethod);
                break;
            }
            case "Integer":
            case "Long": {
                VariableDeclarator variableDeclarator = new VariableDeclarator(new ClassOrInterfaceType(null, "long"), "req");
                variableDeclarator.setInitializer("0");
                body.addStatement(new VariableDeclarationExpr(variableDeclarator));

                break;
            }

            case "MultipartFile": {
                // todo solve this one
                // dependencies.add("org.springframework.web.multipart.MultipartFile");
                ClassOrInterfaceType multipartFile = new ClassOrInterfaceType(null, "MultipartFile");
                VariableDeclarator variableDeclarator = new VariableDeclarator(multipartFile, "req");
                MethodCallExpr methodCallExpr = new MethodCallExpr("uploadFile");
                methodCallExpr.addArgument(new StringLiteralExpr(testMethod.getNameAsString()));
                variableDeclarator.setInitializer(methodCallExpr);
                getBody(testMethod).addStatement(new VariableDeclarationExpr(variableDeclarator));
                break;
            }

            case "Object": {
                // SOme methods incorrectly have their DTO listed as of type Object. We will treat
                // as a String
                prepareBody("java.lang.String", new ClassOrInterfaceType(null, "String"), "new String", testMethod);
                break;
            }

            default:
                ClassOrInterfaceType csiGridDtoType = new ClassOrInterfaceType(null, paramClassName);
                VariableDeclarator variableDeclarator = new VariableDeclarator(csiGridDtoType, "req");
                ObjectCreationExpr objectCreationExpr = new ObjectCreationExpr(null, csiGridDtoType, new NodeList<>());
                variableDeclarator.setInitializer(objectCreationExpr);
                VariableDeclarationExpr variableDeclarationExpr = new VariableDeclarationExpr(variableDeclarator);
                body.addStatement(variableDeclarationExpr);
        }
        if (preConditions != null) {
            for (Expression expr : preConditions) {
                if (expr.isMethodCallExpr()) {
                    String s = expr.toString();
                    if (s.contains("set")) {
                        body.addStatement(s.replaceFirst("^[^.]+\\.", "req.") + ";");
                    }
                }
            }
        }
        if (cdecl.getNameAsString().equals("MultipartFile")) {
            makePost.addArgument(new NameExpr("req"));
            testMethod.addThrownException(new ClassOrInterfaceType(null, "IOException"));
        } else {
            MethodCallExpr writeValueAsStringCall = new MethodCallExpr(new NameExpr("objectMapper"), "writeValueAsString");
            writeValueAsStringCall.addArgument(new NameExpr("req"));
            makePost.addArgument(writeValueAsStringCall);
            testMethod.addThrownException(new ClassOrInterfaceType(null, "JsonProcessingException"));
        }
    }

    private void prepareRequest(MethodCallExpr makePost, ControllerRequest request, BlockStmt body) {
        makePost.addArgument(new NameExpr("headers"));
        addQueryParams(makePost, request, body);
    }

    private void prepareBody(String e, ClassOrInterfaceType paramClassName, String name, MethodDeclaration testMethod) {
        // todo solve this one
        // dependencies.add(e);
        VariableDeclarator variableDeclarator = new VariableDeclarator(paramClassName, "req");
        MethodCallExpr methodCallExpr = new MethodCallExpr(name);
        variableDeclarator.setInitializer(methodCallExpr);
        VariableDeclarationExpr variableDeclarationExpr = new VariableDeclarationExpr(variableDeclarator);

        getBody(testMethod).addStatement(variableDeclarationExpr);
    }


    MethodDeclaration buildTestMethod(MethodDeclaration md) {
        MethodDeclaration testMethod = new MethodDeclaration();

        NormalAnnotationExpr testCaseTypeAnnotation = new NormalAnnotationExpr();
        testCaseTypeAnnotation.setName("TestCaseType");
        testCaseTypeAnnotation.addPair("types", "{TestType.BVT, TestType.REGRESSION}");

        testMethod.addAnnotation(testCaseTypeAnnotation);
        testMethod.addAnnotation("Test");
        StringBuilder paramNames = new StringBuilder();
        for(var param : md.getParameters()) {
            param.getAnnotationByName("PathVariable").ifPresent(ann -> {
                paramNames.append(param.getNameAsString().substring(0, 1).toUpperCase())
                        .append(param.getNameAsString().substring(1));
            });
        }

        String testName = String.valueOf(md.getName());
        if (paramNames.isEmpty()) {
            testName += "Test";
        } else {
            testName += "By" + paramNames + "Test";

        }

        if (testMethodNames.contains(testName)) {
            testName += "_" + (char)('A' + testMethodNames.size()  % 26 -1);
        }
        testMethodNames.add(testName);
        testMethod.setName(testName);

        BlockStmt body = new BlockStmt();

        testMethod.setType(new VoidType());

        testMethod.setBody(body);
        return testMethod;
    }


    /**
     * Given an annotation for a method in a controller find the full path in the url
     * @param annotation a GetMapping, PostMapping etc
     * @return the path url component
     */
    private String getPath(AnnotationExpr annotation) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            return commonPath + annotation.asSingleMemberAnnotationExpr().getMemberValue().toString();
        } else if (annotation.isNormalAnnotationExpr()) {
            NormalAnnotationExpr normalAnnotation = annotation.asNormalAnnotationExpr();
            for (var pair : normalAnnotation.getPairs()) {
                if (pair.getNameAsString().equals("path") || pair.getNameAsString().equals("value")) {
                    String pairValue = pair.getValue().toString();
                    String[] parts = pairValue.split(",");
                    if(parts.length == 2) {
                        return parts[0].substring(1).replace("\"","").strip();
                    }
                    return commonPath + pair.getValue().toString();
                }
            }
        }
        return commonPath;
    }

    /**
     * Handle the RequestParam and PathVariables.
     * It may happen that the PathVariable annotation will result in the parameter having a
     * different name from the variable name. So we will build that map for better tracking
     *
     * @param md the method declaration
     * @param request the controller request
     */
    void handleURIVariables(MethodDeclaration md, ControllerRequest request) {
        for(var param : md.getParameters()) {
            String nameAsString = param.getNameAsString();
            String queryParam = AbstractCompiler.getRestParameterName(param);
            Variable value = argumentGenerator.getArguments().get(queryParam);
            if (value != null && value.getValue() != null) {
                request.addQueryParameter(nameAsString, value.getValue().toString());

                if (param.getAnnotationByName("PathVariable").isPresent()) {
                    request.setPath(request.getPath().replace("{" + nameAsString + "}",
                            value.getValue().toString()));
                }
            }
        }
    }

    private void addHttpStatusCheck(BlockStmt blockStmt, int statusCode)
    {
        MethodCallExpr getStatusCodeCall = new MethodCallExpr(new NameExpr("response"), "getStatusCode");

        MethodCallExpr assertTrueCall = new MethodCallExpr(new NameExpr("Assert"), "assertEquals");
        assertTrueCall.addArgument(new IntegerLiteralExpr(statusCode));
        assertTrueCall.addArgument(getStatusCodeCall);

        blockStmt.addStatement(new ExpressionStmt(assertTrueCall));
    }


    /**
     * Of the various params in the method, which one is the RequestBody
     * @param md a method argument
     * @return the parameter identified as the RequestBody
     */
    public static Parameter findRequestBody(MethodDeclaration md) {

        for(var param : md.getParameters()) {
            if(param.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("RequestBody"))) {
                return param;
            }
        }
        return null;
    }

    public void setCommonPath(String commonPath) {
        this.commonPath = commonPath;
    }

    @Override
    public CompilationUnit getCompilationUnit() {
        return gen;
    }

    public void setCompilationUnit(CompilationUnit gen) {
        this.gen = gen;
    }

    @Override
    public void setPreconditions(List<Expression> preconditions) {
        this.preConditions = preconditions;
    }

    @Override
    public boolean isBranched() {
        return branched;
    }

    @Override
    public void setBranched(boolean branched) {
        this.branched = branched;
    }
}

