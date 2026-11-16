package cl.exobrienx.wallet.ledger;

import cl.exobrienx.wallet.TestcontainersConfiguration;
import cl.exobrienx.wallet.support.ApiClient;
import cl.exobrienx.wallet.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ReconciliationIT {

    private static final UUID SYSTEM_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Test
    void ledgerIsBalancedAfterRegularOperations() {
        ApiClient api = new ApiClient(mvc);
        String token = api.newClientToken();
        UUID account = api.openAccount(token);
        String other = api.newClientToken();
        String destination = api.accountNumber(other, api.openAccount(other));
        api.deposit(token, account, 80_000);
        api.withdraw(token, account, 15_000);
        api.transfer(token, account, destination, 25_000);

        ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.balanced()).isTrue();
        assertThat(report.totalDebits()).isEqualTo(report.totalCredits()).isPositive();
        assertThat(report.transactionsChecked()).isGreaterThanOrEqualTo(3);
        assertThat(report.unbalancedTransactions()).isEmpty();
    }

    @Test
    void reportsUserAccountsWithNegativeBalance() {
        // Una cuenta en negativo solo puede aparecer tras una reversa de un administrador (fase 6).
        // Aquí se simula insertando los asientos directamente: el libro sigue cuadrado, pero la
        // conciliación debe señalar la cuenta.
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, first_name, last_name, rut, role)
                VALUES (?, ?, 'x', 'Ana', 'Pérez', ?, 'USER')""",
                userId, userId + "@test.cl", TestData.randomRut());
        jdbc.update("""
                INSERT INTO accounts (id, user_id, account_number, currency, status, type)
                VALUES (?, ?, ?, 'CLP', 'ACTIVE', 'USER')""",
                accountId, userId, TestData.randomAccountNumber());
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO transactions (id, type, status, idempotency_key, initiated_by, completed_at)
                    VALUES (?, 'WITHDRAWAL', 'COMPLETED', ?, ?, now())""", txId, txId.toString(), userId);
            jdbc.update("""
                    INSERT INTO ledger_entries (id, transaction_id, account_id, amount, direction)
                    VALUES (?, ?, ?, 7000, 'DEBIT'), (?, ?, ?, 7000, 'CREDIT')""",
                    UUID.randomUUID(), txId, accountId, UUID.randomUUID(), txId, SYSTEM_ACCOUNT);
        });

        ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.balanced()).isTrue();
        assertThat(report.negativeUserAccounts())
                .anySatisfy(negative -> {
                    assertThat(negative.accountId()).isEqualTo(accountId);
                    assertThat(negative.balance()).isEqualTo(-7_000);
                });
    }
}
