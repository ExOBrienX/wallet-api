package cl.exobrienx.wallet.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Concilia el libro contable: verifica que el dinero no se crea ni se destruye.
 * <p>
 * Se usa SQL directo porque son agregaciones sobre todo el libro: cargar las entidades en
 * memoria para sumarlas sería mucho más lento y no aportaría nada.
 */
@Service
public class ReconciliationService {

    private final JdbcClient jdbc;
    private final Clock clock;

    public ReconciliationService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * {@code REPEATABLE_READ} hace que todas las consultas vean la misma foto de la base: si
     * mientras tanto se registra una transferencia, no puede aparecer contada en una suma y no
     * en la otra.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport reconcile() {
        Totals totals = jdbc.sql("""
                        SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)  AS debits,
                               COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS credits
                        FROM ledger_entries""")
                .query((rs, row) -> new Totals(rs.getLong("debits"), rs.getLong("credits")))
                .single();

        long transactionsChecked = jdbc.sql("SELECT COUNT(*) FROM transactions WHERE status IN ('COMPLETED', 'REVERSED')")
                .query(Long.class).single();

        List<UUID> unbalanced = jdbc.sql("""
                        SELECT t.id
                        FROM transactions t
                        LEFT JOIN ledger_entries e ON e.transaction_id = t.id
                        WHERE t.status IN ('COMPLETED', 'REVERSED')
                        GROUP BY t.id
                        HAVING COUNT(e.id) < 2
                            OR COALESCE(SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END), 0) <> 0
                        ORDER BY t.id""")
                .query(UUID.class).list();

        List<ReconciliationReport.NegativeAccount> negative = jdbc.sql("""
                        SELECT a.id, a.account_number,
                               SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END) AS balance
                        FROM accounts a
                        JOIN ledger_entries e ON e.account_id = a.id
                        WHERE a.type = 'USER'
                        GROUP BY a.id, a.account_number
                        HAVING SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END) < 0
                        ORDER BY balance""")
                .query((rs, row) -> new ReconciliationReport.NegativeAccount(
                        rs.getObject("id", UUID.class), rs.getString("account_number"), rs.getLong("balance")))
                .list();

        boolean balanced = totals.debits() == totals.credits() && unbalanced.isEmpty();
        return new ReconciliationReport(balanced, totals.debits(), totals.credits(),
                totals.credits() - totals.debits(), transactionsChecked,
                unbalanced, negative, clock.instant());
    }

    private record Totals(long debits, long credits) {
    }
}
