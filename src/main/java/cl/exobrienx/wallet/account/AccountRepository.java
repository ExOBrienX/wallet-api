package cl.exobrienx.wallet.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByUserIdOrderByCreatedAtAsc(UUID userId);

    long countByUserId(UUID userId);

    boolean existsByAccountNumber(String accountNumber);

    Optional<Account> findByAccountNumber(String accountNumber);

    /**
     * Carga la cuenta bloqueando su fila ({@code SELECT ... FOR UPDATE}) hasta el fin de la
     * transacción. Mientras tanto, cualquier otra operación que quiera bloquearla espera; así
     * el saldo calculado no puede cambiar entre la validación y el registro del movimiento.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(UUID id);
}
