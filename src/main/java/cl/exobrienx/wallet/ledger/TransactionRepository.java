package cl.exobrienx.wallet.ledger;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    boolean existsByInitiatedByIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    /** Bloquea la transacción para que dos reversas simultáneas no la reviertan dos veces. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transaction t where t.id = :id")
    Optional<Transaction> findByIdForUpdate(UUID id);
}
