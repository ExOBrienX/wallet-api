package cl.exobrienx.wallet.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resultado de conciliar el libro contable.
 *
 * @param balanced                 true si el libro cuadra: débitos = créditos y toda transacción
 *                                 cuadra por sí misma
 * @param totalDebits              suma de todos los débitos
 * @param totalCredits             suma de todos los créditos
 * @param difference               créditos menos débitos; cero si el libro cuadra
 * @param unbalancedTransactions   transacciones cuyos asientos no suman cero o tienen menos de dos
 *                                 asientos (la base lo impide; si aparece alguna, hay un problema grave)
 * @param negativeUserAccounts     cuentas de cliente con saldo negativo (solo posible tras una
 *                                 reversa hecha por un administrador); no rompe el cuadre
 */
public record ReconciliationReport(
        boolean balanced,
        long totalDebits,
        long totalCredits,
        long difference,
        long transactionsChecked,
        List<UUID> unbalancedTransactions,
        List<NegativeAccount> negativeUserAccounts,
        Instant checkedAt) {

    public record NegativeAccount(UUID accountId, String accountNumber, long balance) {
    }
}
