package cl.exobrienx.wallet.admin.dto;

import cl.exobrienx.wallet.ledger.TransactionType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resultado de una reversa.
 *
 * @param affectedAccounts cuentas de cliente afectadas y su saldo resultante
 */
public record ReversalResponse(
        UUID reversalTransactionId,
        UUID originalTransactionId,
        TransactionType originalType,
        String description,
        List<AffectedAccount> affectedAccounts,
        Instant createdAt) {

    /**
     * @param negative true si la reversa dejó la cuenta en negativo: el cliente ya había usado
     *                 el dinero (contracargo) y no podrá enviar hasta volver a saldo positivo
     */
    public record AffectedAccount(UUID accountId, String accountNumber, long balanceAfter, boolean negative) {

        public static AffectedAccount of(UUID accountId, String accountNumber, long balanceAfter) {
            return new AffectedAccount(accountId, accountNumber, balanceAfter, balanceAfter < 0);
        }
    }
}
