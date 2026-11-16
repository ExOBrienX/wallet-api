package cl.exobrienx.wallet.transfer.dto;

import cl.exobrienx.wallet.account.Account;
import cl.exobrienx.wallet.ledger.Transaction;
import cl.exobrienx.wallet.ledger.TransactionStatus;
import cl.exobrienx.wallet.ledger.TransactionType;

import java.time.Instant;
import java.util.UUID;

/**
 * Resultado de un depósito o retiro.
 *
 * @param balanceAfter saldo de la cuenta inmediatamente después de la operación
 */
public record MovementResponse(
        UUID transactionId,
        TransactionType type,
        TransactionStatus status,
        UUID accountId,
        String accountNumber,
        long amount,
        String description,
        long balanceAfter,
        Instant createdAt,
        Instant completedAt) {

    public static MovementResponse of(Transaction transaction, Account account, long amount, long balanceAfter) {
        return new MovementResponse(transaction.getId(), transaction.getType(), transaction.getStatus(),
                account.getId(), account.getAccountNumber(), amount, transaction.getDescription(),
                balanceAfter, transaction.getCreatedAt(), transaction.getCompletedAt());
    }
}
