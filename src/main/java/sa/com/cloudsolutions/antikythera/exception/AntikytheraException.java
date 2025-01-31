package sa.com.cloudsolutions.antikythera.exception;

public class AntikytheraException  extends RuntimeException {

    public AntikytheraException(String message) {
        super(message);
    }

    public AntikytheraException(String message, Throwable cause) {
        super(message, cause);
    }

    public AntikytheraException(Throwable cause) {
        super(cause);
    }
}
