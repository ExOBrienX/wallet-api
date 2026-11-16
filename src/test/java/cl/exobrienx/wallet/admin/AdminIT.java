package cl.exobrienx.wallet.admin;

import cl.exobrienx.wallet.TestcontainersConfiguration;
import cl.exobrienx.wallet.auth.AuthService;
import cl.exobrienx.wallet.support.ApiClient;
import cl.exobrienx.wallet.support.TestData;
import cl.exobrienx.wallet.user.NewUser;
import cl.exobrienx.wallet.user.Role;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdminIT {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private String admin;
    private String client;
    private UUID account;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        String email = TestData.uniqueEmail();
        authService.createUser(new NewUser(email, ApiClient.PASSWORD, "Admin", "Test", null, null, Role.ADMIN));
        admin = api.login(email);
        client = api.newClientToken();
        account = api.openAccount(client);
    }

    // --- bloqueo de cuentas --------------------------------------------------------

    @Test
    void blockedAccountCannotOperateUntilReactivated() {
        api.deposit(client, account, 10_000);

        MvcTestResult blocked = changeStatus(account, "BLOCKED");
        assertThat(blocked).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("BLOCKED");
        assertThat(api.withdraw(client, account, 1_000)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);

        assertThat(changeStatus(account, "ACTIVE")).hasStatusOk();
        assertThat(api.withdraw(client, account, 1_000)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void systemAccountCannotBeBlocked() {
        assertThat(changeStatus(UUID.fromString("00000000-0000-0000-0000-000000000001"), "BLOCKED"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void invalidStatusIsRejected() {
        assertThat(patch("/api/admin/accounts/" + account + "/status", """
                {"status": "CERRADA"}""", admin)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void clientsCannotUseAdminEndpoints() {
        assertThat(changeStatusAs(client, account, "BLOCKED")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(api.get("/api/admin/reconciliation", client)).hasStatus(HttpStatus.FORBIDDEN);
    }

    // --- reversas (regla 9) ----------------------------------------------------------

    @Test
    void reversingDepositRestoresBalanceAndMarksOriginal() {
        UUID deposit = transactionId(api.deposit(client, account, 8_000));

        MvcTestResult result = reverse(deposit, "Depósito duplicado por error");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.originalType").isEqualTo("DEPOSIT");
        assertThat(result).bodyJson().extractingPath("$.description").isEqualTo("Reversa de deposit: Depósito duplicado por error");
        assertThat(result).bodyJson().extractingPath("$.affectedAccounts[0].balanceAfter").isEqualTo(0);
        assertThat(api.balance(client, account)).isZero();
        assertThat(statusOf(deposit)).isEqualTo("REVERSED");
        UUID reversal = transactionId(result, "$.reversalTransactionId");
        assertThat(jdbc.queryForObject("SELECT reverses_transaction_id FROM transactions WHERE id = ?",
                UUID.class, reversal)).isEqualTo(deposit);
    }

    @Test
    void transactionCanOnlyBeReversedOnce() {
        UUID deposit = transactionId(api.deposit(client, account, 5_000));
        UUID reversal = transactionId(reverse(deposit, null), "$.reversalTransactionId");

        assertThat(reverse(deposit, null)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.detail").isEqualTo("La transacción ya fue revertida.");
        assertThat(reverse(reversal, null)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.detail").isEqualTo("Una reversa no se puede revertir.");
    }

    @Test
    void reversalCanLeaveAccountNegativeLikeAChargeback() {
        String other = api.newClientToken();
        UUID otherAccount = api.openAccount(other);
        api.deposit(client, account, 50_000);
        UUID transfer = transactionId(api.transfer(client, account, api.accountNumber(other, otherAccount), 30_000));
        // El destinatario gasta el dinero recibido antes de la reversa.
        api.withdraw(other, otherAccount, 25_000);

        MvcTestResult result = reverse(transfer, "Transferencia reclamada por fraude");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(api.balance(client, account)).isEqualTo(50_000);
        assertThat(api.balance(other, otherAccount)).isEqualTo(-25_000);
        assertThat(result).bodyJson().extractingPath("$.affectedAccounts[?(@.negative == true)].balanceAfter")
                .asArray().containsExactly(-25_000);

        // Con saldo negativo no puede enviar dinero, pero sí recibir.
        assertThat(api.withdraw(other, otherAccount, 1)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.deposit(other, otherAccount, 30_000)).hasStatus(HttpStatus.CREATED);
        assertThat(api.balance(other, otherAccount)).isEqualTo(5_000);
    }

    @Test
    void reconciliationReportsNegativeAccountsAndStaysBalanced() {
        String other = api.newClientToken();
        UUID otherAccount = api.openAccount(other);
        api.deposit(client, account, 20_000);
        UUID transfer = transactionId(api.transfer(client, account, api.accountNumber(other, otherAccount), 20_000));
        api.withdraw(other, otherAccount, 20_000);
        reverse(transfer, null);

        MvcTestResult result = api.get("/api/admin/reconciliation", admin);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.balanced").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.difference").isEqualTo(0);
        assertThat(result).bodyJson().extractingPath("$.negativeUserAccounts[*].accountId")
                .asArray().contains(otherAccount.toString());
    }

    @Test
    void unknownTransactionReturnsNotFound() {
        assertThat(reverse(UUID.randomUUID(), null)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void concurrentReversalsOfSameTransactionApplyOnlyOnce() throws Exception {
        UUID deposit = transactionId(api.deposit(client, account, 9_000));
        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return reverse(deposit, null).getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 422).hasSize(threads - 1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(api.balance(client, account)).isZero();
    }

    // --- helpers -------------------------------------------------------------------

    private MvcTestResult changeStatus(UUID accountId, String status) {
        return changeStatusAs(admin, accountId, status);
    }

    private MvcTestResult changeStatusAs(String token, UUID accountId, String status) {
        return patch("/api/admin/accounts/" + accountId + "/status", """
                {"status": "%s"}""".formatted(status), token);
    }

    private MvcTestResult patch(String uri, String json, String token) {
        return mvc.patch().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private MvcTestResult reverse(UUID transactionId, String reason) {
        String body = reason == null ? "{}" : """
                {"reason": "%s"}""".formatted(reason);
        return api.post("/api/admin/transactions/" + transactionId + "/reverse", body, admin);
    }

    private static UUID transactionId(MvcTestResult result) {
        return transactionId(result, "$.transactionId");
    }

    private static UUID transactionId(MvcTestResult result, String path) {
        assertThat(result.getResponse().getStatus()).isBetween(200, 299);
        return UUID.fromString(ApiClient.read(result, path));
    }

    private String statusOf(UUID transactionId) {
        return jdbc.queryForObject("SELECT status FROM transactions WHERE id = ?", String.class, transactionId);
    }
}
