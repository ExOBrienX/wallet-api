package cl.exobrienx.wallet.transfer;

import cl.exobrienx.wallet.TestcontainersConfiguration;
import cl.exobrienx.wallet.support.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CashMovementIT {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private String token;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        token = api.newClientToken();
        accountId = api.openAccount(token);
    }

    @Test
    void depositIncreasesBalanceAndReturnsTransaction() {
        MvcTestResult result = api.deposit(token, accountId, 25_000);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("DEPOSIT");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");
        assertThat(result).bodyJson().extractingPath("$.amount").isEqualTo(25_000);
        assertThat(result).bodyJson().extractingPath("$.balanceAfter").isEqualTo(25_000);
        assertThat(result).bodyJson().extractingPath("$.description").isEqualTo("Test");
        assertThat(result).bodyJson().hasPath("$.completedAt");
    }

    @Test
    void depositIsRecordedAsBalancedDoubleEntry() {
        UUID transactionId = UUID.fromString(ApiClient.read(api.deposit(token, accountId, 8_000), "$.transactionId"));

        Long net = jdbc.queryForObject("""
                SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END)
                FROM ledger_entries WHERE transaction_id = ?""", Long.class, transactionId);
        Integer systemEntries = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_entries
                WHERE transaction_id = ? AND account_id = '00000000-0000-0000-0000-000000000001'
                  AND direction = 'DEBIT'""", Integer.class, transactionId);

        assertThat(net).isZero();
        assertThat(systemEntries).isEqualTo(1);
    }

    @Test
    void withdrawalDecreasesBalance() {
        api.deposit(token, accountId, 10_000);

        MvcTestResult result = api.withdraw(token, accountId, 4_000);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("WITHDRAWAL");
        assertThat(result).bodyJson().extractingPath("$.balanceAfter").isEqualTo(6_000);
    }

    @Test
    void withdrawalCanLeaveExactlyZero() {
        api.deposit(token, accountId, 10_000);

        assertThat(api.withdraw(token, accountId, 10_000)).hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.balanceAfter").isEqualTo(0);
    }

    @Test
    void withdrawalWithInsufficientFundsIsRejectedAndBalanceUnchanged() {
        api.deposit(token, accountId, 10_000);

        MvcTestResult result = api.withdraw(token, accountId, 10_001);

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Saldo insuficiente");
        assertThat(api.get("/api/accounts/" + accountId, token)).bodyJson()
                .extractingPath("$.balance").isEqualTo(10_000);
    }

    @Test
    void cannotOperateOnAccountOfAnotherUser() {
        UUID othersAccount = api.openAccount(api.newClientToken());

        assertThat(api.deposit(token, othersAccount, 1_000)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void blockedAccountCannotDepositOrWithdraw() {
        api.deposit(token, accountId, 5_000);
        jdbc.update("UPDATE accounts SET status = 'BLOCKED' WHERE id = ?", accountId);

        assertThat(api.deposit(token, accountId, 1_000)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.title").isEqualTo("Cuenta bloqueada");
        assertThat(api.withdraw(token, accountId, 1_000)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    // --- Idempotency-Key ---------------------------------------------------------

    @Test
    void missingIdempotencyKeyIsRejected() {
        MvcTestResult result = mvc.post().uri("/api/deposits")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountId": "%s", "amount": 1000}""".formatted(accountId))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void repeatedIdempotencyKeyDoesNotMoveMoneyTwice() {
        String key = UUID.randomUUID().toString();
        assertThat(api.movement("/api/deposits", token, accountId, 3_000, key)).hasStatus(HttpStatus.CREATED);

        MvcTestResult repeated = api.movement("/api/deposits", token, accountId, 3_000, key);

        // Se devuelve la respuesta original (201) sin registrar un segundo depósito.
        assertThat(repeated).hasStatus(HttpStatus.CREATED).hasHeader("Idempotent-Replayed", "true");
        assertThat(api.get("/api/accounts/" + accountId, token)).bodyJson()
                .extractingPath("$.balance").isEqualTo(3_000);
    }

    @Test
    void sameIdempotencyKeyCanBeUsedByDifferentUsers() {
        String key = UUID.randomUUID().toString();
        String otherToken = api.newClientToken();
        UUID otherAccount = api.openAccount(otherToken);

        assertThat(api.movement("/api/deposits", token, accountId, 1_000, key)).hasStatus(HttpStatus.CREATED);
        assertThat(api.movement("/api/deposits", otherToken, otherAccount, 1_000, key)).hasStatus(HttpStatus.CREATED);
    }

    // --- validaciones --------------------------------------------------------------

    @Test
    void amountMustBePositiveAndWithinLimit() {
        assertThat(api.deposit(token, accountId, 0)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().hasPath("$.errors.amount");
        assertThat(api.deposit(token, accountId, -500)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(api.deposit(token, accountId, 100_000_001)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void decimalAmountsAreRejectedInsteadOfTruncated() {
        // Regla 7: los montos son pesos enteros. Un monto con decimales no debe convertirse
        // silenciosamente en otro (1000.50 → 1000), sino rechazarse.
        MvcTestResult result = mvc.post().uri("/api/deposits")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountId": "%s", "amount": 1000.50}""".formatted(accountId))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(api.get("/api/accounts/" + accountId, token)).bodyJson()
                .extractingPath("$.balance").isEqualTo(0);
    }

    @Test
    void depositRequiresAuthentication() {
        assertThat(mvc.post().uri("/api/deposits").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void systemLedgerAlwaysNetsToZero() {
        api.deposit(token, accountId, 50_000);
        api.withdraw(token, accountId, 12_345);

        // Suma de todos los saldos (incluida la cuenta del sistema): siempre cero en doble entrada.
        Long total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
                FROM ledger_entries""", Long.class);
        assertThat(total).isZero();
    }
}
