package sa.com.cloudsolutions.antikythera.evaluator.functional;

import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.WildcardType;
import sa.com.cloudsolutions.antikythera.evaluator.EvaluatorFactory;
import sa.com.cloudsolutions.antikythera.exception.AntikytheraException;

public class RunnableEvaluator extends FPEvaluator implements Runnable {

    public RunnableEvaluator(EvaluatorFactory.Context context) {
        super(context);
        this.enclosure = context.getEnclosure();
    }

    @Override
    public Type getType() {
        return new ClassOrInterfaceType()
                .setName("Runnable")
                .setTypeArguments(
                        new WildcardType()
                );
    }

    @Override
    public void run() {
        try {
            executeMethod(methodDeclaration);
        } catch (ReflectiveOperationException e) {
            throw new AntikytheraException(e);
        }
    }
}
