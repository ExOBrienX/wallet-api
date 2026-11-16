package cl.exobrienx.wallet.ledger;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class TransactionNotReversibleException extends DomainException {

    public TransactionNotReversibleException(Transaction transaction) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "Transacción no reversible", detail(transaction));
    }

    private static String detail(Transaction transaction) {
        if (transaction.getType() == TransactionType.REVERSAL) {
            return "Una reversa no se puede revertir.";
        }
        if (transaction.getStatus() == TransactionStatus.REVERSED) {
            return "La transacción ya fue revertida.";
        }
        return "Solo se pueden revertir transacciones completadas.";
    }
}
