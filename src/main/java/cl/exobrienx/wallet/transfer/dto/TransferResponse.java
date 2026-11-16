package cl.exobrienx.wallet.transfer.dto;

import cl.exobrienx.wallet.ledger.Transaction;
import cl.exobrienx.wallet.ledger.TransactionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Comprobante de una transferencia.
 *
 * @param recipientName nombre del destinatario parcialmente oculto ("Matías G."), para que quien
 *                      transfiere confirme que el destino es correcto sin exponer datos completos
 * @param balanceAfter  saldo de la cuenta de origen después de la transferencia
 */
public record TransferResponse(
        UUID transactionId,
        TransactionStatus status,
        UUID sourceAccountId,
        String sourceAccountNumber,
        String destinationAccountNumber,
        String recipientName,
        long amount,
        String description,
        long balanceAfter,
        Instant createdAt,
        Instant completedAt) {

    public static TransferResponse of(Transaction transaction, UUID sourceAccountId, String sourceAccountNumber,
                                      String destinationAccountNumber, String recipientName,
                                      long amount, long balanceAfter) {
        return new TransferResponse(transaction.getId(), transaction.getStatus(), sourceAccountId,
                sourceAccountNumber, destinationAccountNumber, recipientName, amount,
                transaction.getDescription(), balanceAfter, transaction.getCreatedAt(), transaction.getCompletedAt());
    }
}
