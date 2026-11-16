package cl.exobrienx.wallet.auth;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

import java.time.Duration;

public class TooManyLoginAttemptsException extends DomainException {

    private final Duration retryAfter;

    public TooManyLoginAttemptsException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos",
                "Demasiados intentos fallidos de inicio de sesión. Intenta nuevamente en "
                        + Math.max(1, retryAfter.toMinutes()) + " minutos.");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
