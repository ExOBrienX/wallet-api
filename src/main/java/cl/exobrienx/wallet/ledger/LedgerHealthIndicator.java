package cl.exobrienx.wallet.ledger;

import cl.exobrienx.wallet.account.Account;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Verifica que el libro contable esté en condiciones de registrar movimientos:
 * <ul>
 *   <li>existe la cuenta interna del sistema, contraparte de todo depósito y retiro;</li>
 *   <li>están instalados los triggers que protegen el libro (inmutabilidad y cuadre).</li>
 * </ul>
 * Forma parte del grupo de readiness: si falla, la instancia no debe recibir tráfico. Son
 * consultas de costo constante, aptas para ejecutarse cada pocos segundos; la conciliación
 * completa del libro no se incluye aquí porque su costo crece con la cantidad de movimientos.
 * <p>
 * Extiende {@link AbstractHealthIndicator} para que cualquier excepción (por ejemplo, la base
 * de datos caída) se informe como DOWN, con código 503, y no como un error 500.
 */
@Component("ledger")
public class LedgerHealthIndicator extends AbstractHealthIndicator {

    static final Set<String> REQUIRED_TRIGGERS = Set.of(
            "tg_ledger_entries_immutable", "tg_ledger_entries_balanced", "tg_transactions_immutable");

    private final JdbcClient jdbc;

    public LedgerHealthIndicator(JdbcClient jdbc) {
        super("No se pudo verificar el libro contable");
        this.jdbc = jdbc;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        boolean systemAccount = jdbc.sql("SELECT COUNT(*) FROM accounts WHERE id = ? AND type = 'SYSTEM'")
                .param(Account.SYSTEM_ACCOUNT_ID)
                .query(Long.class).single() == 1;

        List<String> installed = jdbc.sql("""
                        SELECT tgname FROM pg_trigger
                        WHERE NOT tgisinternal AND tgname IN ('tg_ledger_entries_immutable',
                              'tg_ledger_entries_balanced', 'tg_transactions_immutable')""")
                .query(String.class).list();
        Set<String> missing = new TreeSet<>(REQUIRED_TRIGGERS);
        installed.forEach(missing::remove);

        (systemAccount && missing.isEmpty() ? builder.up() : builder.down())
                .withDetail("systemAccount", systemAccount ? "presente" : "falta")
                .withDetail("protectionTriggers", missing.isEmpty() ? "instalados" : "faltan: " + missing);
    }
}
