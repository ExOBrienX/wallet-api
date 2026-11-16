package cl.exobrienx.wallet.common.error;

import org.springframework.http.HttpStatus;

import java.util.Locale;

/**
 * Base de las excepciones de negocio. Cada subclase define el estado HTTP y el título con que se
 * responde; el {@code GlobalExceptionHandler} las convierte en {@code ProblemDetail}. El mensaje de
 * la excepción se usa como {@code detail}, así que debe ser apto para el cliente.
 * <p>
 * El código de error se deriva del nombre de la clase ({@code InsufficientFundsException} →
 * {@code INSUFFICIENT_FUNDS}), de modo que es estable y no puede quedar desalineado con la excepción.
 */
public abstract class DomainException extends RuntimeException {

    private final HttpStatus status;
    private final String title;

    protected DomainException(HttpStatus status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    /** Código estable del error, pensado para que el cliente decida qué hacer sin leer el texto. */
    public String getCode() {
        return codeOf(getClass());
    }

    static String codeOf(Class<?> exceptionClass) {
        return exceptionClass.getSimpleName()
                .replaceAll("Exception$", "")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toUpperCase(Locale.ROOT);
    }
}
