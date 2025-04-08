package sa.com.cloudsolutions.antikythera.evaluator;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.util.List;


/**
 * Create fake objects using DTO Buddy for DTOs, Entities and possibly other types of classes
 */
public class DTOBuddy {

    protected DTOBuddy() {}

    /**
     * Dynamically create a class matching the given type declaration and then create an instance.
     *
     * We will iterate through all the fields declared in the source code and make fake fields
     *  accordingly so that they show up in reflective inspections.
     *
     * @param interceptor the MethodInterceptor to be used for the dynamic class.
     * @return an instance of the class that was faked.
     * @throws ReflectiveOperationException If an error occurs during reflection operations.
     */
    public static Class<?> createDynamicClass(MethodInterceptor interceptor) throws ClassNotFoundException {
        Evaluator eval = interceptor.getEvaluator();
        CompilationUnit cu = eval.getCompilationUnit();
        TypeDeclaration<?> dtoType = AbstractCompiler.getMatchingType(cu, eval.getClassName()).orElseThrow();
        String className = dtoType.getNameAsString();

        Class<?> clazz = AntikytheraRunTime.getInjectedClass(className);
        if (clazz != null) {
            return clazz;
        }

        List<FieldDeclaration> fields = dtoType.getFields();

        ByteBuddy byteBuddy = new ByteBuddy();
        DynamicType.Builder<?> builder = byteBuddy.subclass(Object.class).name(className)
                .method(ElementMatchers.any())
                .intercept(MethodDelegation.to(interceptor));

        for (FieldDeclaration field : fields) {
            VariableDeclarator vd = field.getVariable(0);
            String fieldName = vd.getNameAsString();


            TypeDescription.Generic fieldType = null;
            if (vd.getType().isPrimitiveType()) {
                fieldType = TypeDescription.Generic.OfNonGenericType.ForLoadedType.of(
                        Reflect.getComponentClass(vd.getTypeAsString()));
            }
            else {
                try {
                    String fqn = AbstractCompiler.findFullyQualifiedName(cu, vd.getType().asString());
                    fieldType = TypeDescription.Generic.OfNonGenericType.ForLoadedType.of(Class.forName(fqn));
                } catch (ClassNotFoundException cex) {
                    // This field has a class that's not coming from an external library, but it's only available
                    // as source code. We need to create a dynamic class for it.

                    // then again there are cycles!

//                    String qualifiedName = field.getType().asReferenceType().getQualifiedName();
//                    if (qualifiedName.startsWith("java.util")) {
//                        if (qualifiedName.equals("java.util.List")) {
//                            fieldType = TypeDescription.Generic.Builder.parameterizedType(List.class, Object.class).build();
//                        } else {
//                            Class<?> clazz = Class.forName(qualifiedName);
//                            fieldType = TypeDescription.Generic.OfNonGenericType.ForLoadedType.of(clazz);
//                        }
//                    } else {
//                        Object o = DTOBuddy.createDynamicDTO(qualifiedName, field.getType().asReferenceType().getTypeDeclaration().get());
//                        fieldType = TypeDescription.Generic.OfNonGenericType.ForLoadedType.of(o.getClass());
//                    }
                    continue;
                }
            }

            // Define field
            builder = builder.defineField(fieldName, fieldType, net.bytebuddy.description.modifier.Visibility.PRIVATE);
        }

        clazz = builder. make()
                .load(Evaluator.class.getClassLoader())
                .getLoaded();
        AntikytheraRunTime.addInjectedClass(className, clazz);
        return clazz;
    }

}
