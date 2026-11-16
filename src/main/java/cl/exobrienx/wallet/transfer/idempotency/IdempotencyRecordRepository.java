package cl.exobrienx.wallet.transfer.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, IdempotencyRecord.Key> {

    Optional<IdempotencyRecord> findByUserIdAndKey(UUID userId, String key);

    /**
     * Bloqueo consultivo de PostgreSQL sobre el par usuario + clave, liberado al terminar la
     * transacción. Dos peticiones con la misma clave quedan en fila: la segunda espera a que la
     * primera confirme y luego encuentra su respuesta guardada.
     * <p>
     * Se bloquea un valor (el hash de la clave) y no una fila, porque la primera vez la fila
     * todavía no existe. Si dos claves distintas tuvieran el mismo hash, solo esperarían una a
     * la otra: no afecta la corrección.
     */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))) as lock",
            nativeQuery = true)
    int acquireTransactionLock(String lockKey);
}
