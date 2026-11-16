package cl.exobrienx.wallet.transfer.idempotency;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** La clave ya se usó con otra petición (otra ruta o un cuerpo distinto). */
public class IdempotencyKeyReusedException extends DomainException {

    public IdempotencyKeyReusedException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency-Key reutilizada",
                "Esta Idempotency-Key ya se usó con una petición distinta. Usa una clave nueva para cada operación.");
    }
}
