package cl.exobrienx.wallet.ledger;

import java.time.Instant;
import java.util.UUID;

/**
 * Un movimiento en el historial de una cuenta.
 *
 * @param amount                    monto con signo: positivo si entró dinero, negativo si salió
 * @param counterpartyAccountNumber cuenta del otro lado del movimiento; nulo en depósitos y
 *                                  retiros, cuya contraparte es la cuenta interna del sistema
 */
public record AccountMovement(
        UUID transactionId,
        TransactionType type,
        TransactionStatus status,
        long amount,
        String description,
        String counterpartyAccountNumber,
        Instant createdAt) {

    /** Constructor usado por la consulta JPQL, que entrega el monto sin signo y el sentido. */
    public AccountMovement(UUID transactionId, TransactionType type, TransactionStatus status, EntryDirection direction,
                           long amount, String description, String counterpartyAccountNumber, Instant createdAt) {
        this(transactionId, type, status, direction == EntryDirection.CREDIT ? amount : -amount,
                description, counterpartyAccountNumber, createdAt);
    }
}
