package cl.exobrienx.wallet.transfer.idempotency;

import cl.exobrienx.wallet.common.util.Hashing;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Ejecuta operaciones con {@code Idempotency-Key}:
 * <ul>
 *   <li>Clave nueva: ejecuta la operación y guarda su respuesta.</li>
 *   <li>Clave conocida con la misma petición: devuelve la respuesta guardada sin ejecutar nada.</li>
 *   <li>Clave conocida con otra petición: rechaza con 422.</li>
 * </ul>
 * La operación y el registro de la respuesta ocurren en la misma transacción: o quedan ambos
 * o ninguno. Si la operación falla (por ejemplo, por saldo insuficiente) no se guarda nada y la
 * clave sigue disponible para reintentar.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final JsonMapper jsonMapper;

    public IdempotencyService(IdempotencyRecordRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    /**
     * @param operation identifica el endpoint (ej. "POST /api/transfers"); forma parte del hash,
     *                  así la misma clave no puede reutilizarse en otra operación
     * @param request   cuerpo de la petición, para detectar si la repetición es idéntica
     * @param action    la operación a ejecutar; corre dentro de esta misma transacción
     */
    @Transactional
    public IdempotentResponse execute(UUID userId, String key, String operation, Object request,
                                      HttpStatus successStatus, Supplier<?> action) {
        repository.acquireTransactionLock(userId + ":" + key);
        String requestHash = Hashing.sha256Hex(operation + "\n" + jsonMapper.writeValueAsString(request));

        var existing = repository.findByUserIdAndKey(userId, key);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (!record.getRequestHash().equals(requestHash)) {
                throw new IdempotencyKeyReusedException();
            }
            return new IdempotentResponse(record.getResponseStatus(), record.getResponseBody(), true);
        }

        String body = jsonMapper.writeValueAsString(action.get());
        repository.save(new IdempotencyRecord(userId, key, requestHash, successStatus.value(), body));
        return new IdempotentResponse(successStatus.value(), body, false);
    }
}
