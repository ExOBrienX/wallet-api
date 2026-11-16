package cl.exobrienx.wallet.ledger;

public enum TransactionStatus {
    PENDING,
    COMPLETED,
    FAILED,
    /** Completada y luego revertida por un administrador mediante una transacción REVERSAL. */
    REVERSED
}
