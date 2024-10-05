package sa.com.cloudsolutions.antikythera.parser;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserFieldDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;

import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Class processor will parse a class and track it's dependencies.
 *
 */
public class ClassProcessor extends AbstractCompiler {
    /*
     * The overall strategy:
     *   it is described here even though several different classes are involved.
     *
     *   We are only interested in copying the DTOs from the application under test. Broadly a DTO is a
     *   class is either a return type of a controller or an input to a controller.
     *
     *   A controller has a lot of other dependencies, most notably services and even though respositories
     *   are only supposed to be accessed through services sometimes you find them referred directly in
     *   the controller. These will not be copied across to the test folder.
     */

    /**
     * The logger
     */
    private static final Logger logger = LoggerFactory.getLogger(ClassProcessor.class);

    /**
     * Essentially dependencies are a graph.
     *
     * The key in this map is the fully qualified class. The values will be the other types it
     * refers to.
     */
    protected static final Map<String, Set<Dependency>> dependencies = new HashMap<>();

    static final Set<String> copied = new HashSet<>();

    /**
     * A collection of all imports encountered in a class.
     * This maybe a huge list because sometimes we find wild card imports.
     */
    final Set<ImportDeclaration> allImports = new HashSet<>();

    /**
     * This is a collection of imports that we want to preserve.
     *
     * Most classes contain a bunch of imports that are not used + there will be some that are
     * obsolete after we strip out the unwanted dependencies. Lastly we are trying to avoid
     * asterisk imports, so they are expanded and then the asterisk is removed.
     *
     */
    protected final Set<ImportDeclaration> keepImports = new HashSet<>();

    public ClassProcessor() throws IOException {
        super();
    }

    /**
     * Copy a dependency from the application under test.
     *
     * @param nameAsString a fully qualified class name
     */
    protected void copyDependencies(String nameAsString, Dependency dependency) throws IOException {
        if (dependency.isExternal() || nameAsString.startsWith("org.springframework")) {
            return;
        }

        ClassOrInterfaceDeclaration cdecl = dependency.to.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
        if (cdecl != null &&
                 (!cdecl.getAnnotations().isEmpty() || cdecl.getAnnotationByName("Entity").isPresent())) {
            String targetName = dependency.to.resolve().describe();
            if (!copied.contains(targetName) && targetName.startsWith(AbstractCompiler.basePackage)) {
                try {
                    copied.add(targetName);
                    DTOHandler handler = new DTOHandler();
                    handler.copyDTO(classToPath(targetName));
                    AntikytheraRunTime.addClass(targetName, handler.getCompilationUnit());
                } catch (FileNotFoundException fe) {
                    if (Settings.getProperty("dependencies.on_error").equals("log")) {
                        logger.warn("Could not find {} for copying", targetName);
                    } else {
                        throw fe;
                    }
                }
            }
        }
    }

    /**
     * Find dependencies given a type
     *
     * For each type we encounter, we need to figure out if it's something from the java
     * packages, an external dependency or something from the application under test.
     *
     * If it's a DTO in the AUT, we may need to copy it as well. Those that are identified
     * as being local dependencies in the AUT are added to the dependencies set. Those are
     * destined to be copied once parsing the controller has been completed.
     *
     * Types that are found in external jars are added to the externalDependencies set.
     * These are not copied across with the generated tests.
     *
     * @param type the type to resolve
     */
    void solveTypeDependencies(TypeDeclaration<?> from, Type type)  {

        if (type.isClassOrInterfaceType()) {
            ClassOrInterfaceType classType = type.asClassOrInterfaceType();

            String mainType = classType.getNameAsString();
            NodeList<Type> secondaryType = classType.getTypeArguments().orElse(null);

            if("DateScheduleUtil".equals(mainType) || "Logger".equals(mainType)) {
                /*
                 * Absolutely no reason for a DTO to have DateScheduleUtil or Logger as a dependency.
                 */

                return;
            }
            if (secondaryType != null) {
                for (Type t : secondaryType) {
                    // todo find out the proper way to indentify Type parameters like List<T>
                    if(t.asString().length() != 1 ) {
                        solveTypeDependencies(from, t);
                    }
                }
            }
            else {
                resolveImport(classType);
                createEdge(classType, from);
            }
        }
    }


    /**
     * Converts a class name to an instance name.
     * The usual convention. If we want to create an instance of List that variable is usually
     * called 'list'
     * @param cdecl type declaration
     * @return a variable name as a string
     */
    public static String classToInstanceName(TypeDeclaration<?> cdecl) {
        return classToInstanceName(cdecl.getNameAsString());
    }

    /**
     * Converts a class name to an instance name.
     * @param className as a string
     * @return a variable name as a string
     */
    public static String classToInstanceName(String className) {
        String name = Character.toLowerCase(className.charAt(0)) + className.substring(1);
        if(name.equals("long") || name.equals("int")) {
            return "_" + name;
        }
        return name;
    }

    /**
     * Finds all the classes in a package with in the application under test.
     * We do not search jars, external dependencies or the java standard library.
     *
     * @param packageName the package name
     */
    protected void findMatchingClasses(String packageName) {
        Path p = Paths.get(basePath, packageName.replace(".", "/"));
        File directory = p.toFile();

        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File f : files) {
                    String fname = f.getName();
                    if (fname.endsWith(ClassProcessor.SUFFIX)) {
                        String imp = packageName + "." + fname.substring(0, fname.length() - 5);
                        ImportDeclaration importDeclaration = new ImportDeclaration(imp, false, false);
                        allImports.add(importDeclaration);
                    }
                }
            }
        }
    }

    /**
     * Expands wild card imports.
     * Which means we delete the asterisk import and add all the classes in the package as
     * individual imports.
     *
     * @param cu the compilation unit
     */
    protected void expandWildCards(CompilationUnit cu) {

        for(var imp : cu.getImports()) {
            if(imp.isAsterisk() && !imp.isStatic()) {
                String packageName = imp.getNameAsString();
                if (packageName.startsWith(basePackage)) {
                    findMatchingClasses(packageName);
                }
            }
        }
    }

    protected boolean createEdge(Type typeArg, TypeDeclaration<?> from) {
        try {
            if(typeArg.isPrimitiveType() ||
                    (typeArg.isClassOrInterfaceType() && typeArg.asClassOrInterfaceType().isBoxedType())) {
                Node parent = typeArg.getParentNode().orElse(null);
                if (parent instanceof VariableDeclarator vadecl) {
                    Expression init = vadecl.getInitializer().orElse(null);
                    if (init != null) {
                        JavaParserFieldDeclaration fieldDeclaration =  symbolResolver.resolveDeclaration(init, JavaParserFieldDeclaration.class);
                        ResolvedTypeDeclaration declaringType = fieldDeclaration.declaringType();
                        addEdge(from.getFullyQualifiedName().orElse(null), new Dependency(from, new ClassOrInterfaceType(declaringType.getQualifiedName() )));
                        return true;
                    }
                }
                return false;
            }
            String description = typeArg.resolve().describe();
            if (!description.startsWith("java.")) {
                Dependency dependency = new Dependency(from, typeArg);
                for (var jarSolver : jarSolvers) {
                    if (jarSolver.getKnownClasses().contains(description)) {
                        dependency.setExtension(true);
                        return true;
                    }
                }
                addEdge(from.getFullyQualifiedName().orElse(null), dependency);
            }
        } catch (UnsolvedSymbolException e) {
            logger.debug("Unresolvable {}", typeArg.toString());
        }
        return false;
    }

    protected void addEdge(String className, Dependency dependency) {
        dependencies.computeIfAbsent(className, k -> new HashSet<>()).add(dependency);
    }


    protected void resolveImport(Type type) {
        for (ImportDeclaration importDeclaration : allImports) {
            Name importedName = importDeclaration.getName();
            if (importedName.toString().equals(type.asString())) {
                keepImports.add(importDeclaration);
                return;
            }
        }
    }
}
