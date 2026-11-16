package cl.exobrienx.wallet.ledger;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Ya existe una operación del mismo usuario con esa clave de idempotencia. Garantiza que una
 * petición repetida nunca mueva el dinero dos veces.
 */
public class DuplicateIdempotencyKeyException extends DomainException {

    public DuplicateIdempotencyKeyException() {
        super(HttpStatus.CONFLICT, "Operación ya procesada",
                "Ya existe una operación con esa Idempotency-Key.");
    }
}
