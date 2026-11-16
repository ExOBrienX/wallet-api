package cl.exobrienx.wallet.transfer.idempotency;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Respuesta de una operación idempotente, ya serializada a JSON. Tanto la primera ejecución
 * como las repeticiones devuelven exactamente el mismo cuerpo.
 *
 * @param replayed true si se devolvió una respuesta guardada, sin ejecutar la operación
 */
public record IdempotentResponse(int status, String body, boolean replayed) {

    /** Header informativo para que el cliente sepa que la respuesta es una repetición. */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    public ResponseEntity<String> toResponseEntity() {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(REPLAYED_HEADER, String.valueOf(replayed))
                .body(body);
    }
}
