package cl.exobrienx.wallet.transfer.idempotency;

import cl.exobrienx.wallet.TestcontainersConfiguration;
import cl.exobrienx.wallet.support.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comportamiento del header Idempotency-Key en los tres endpoints que mueven dinero.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class IdempotencyIT {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private String token;
    private UUID account;
    private String destination;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        token = api.newClientToken();
        account = api.openAccount(token);
        String other = api.newClientToken();
        destination = api.accountNumber(other, api.openAccount(other));
        api.deposit(token, account, 100_000);
    }

    @Test
    void repeatedTransferReturnsOriginalResponseWithoutMovingMoneyAgain() throws Exception {
        String key = UUID.randomUUID().toString();

        MvcTestResult first = api.transfer(token, account, destination, 10_000, key);
        MvcTestResult repeated = api.transfer(token, account, destination, 10_000, key);

        assertThat(first).hasStatus(HttpStatus.CREATED).hasHeader(IdempotentResponse.REPLAYED_HEADER, "false");
        assertThat(repeated).hasStatus(HttpStatus.CREATED).hasHeader(IdempotentResponse.REPLAYED_HEADER, "true");
        assertThat(repeated.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(api.balance(token, account)).isEqualTo(90_000);
    }

    @Test
    void repeatedDepositAndWithdrawalAreAlsoReplayed() {
        String depositKey = UUID.randomUUID().toString();
        String withdrawalKey = UUID.randomUUID().toString();

        api.movement("/api/deposits", token, account, 5_000, depositKey);
        api.movement("/api/deposits", token, account, 5_000, depositKey);
        api.movement("/api/withdrawals", token, account, 2_000, withdrawalKey);
        assertThat(api.movement("/api/withdrawals", token, account, 2_000, withdrawalKey))
                .hasHeader(IdempotentResponse.REPLAYED_HEADER, "true");

        assertThat(api.balance(token, account)).isEqualTo(103_000);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        String key = UUID.randomUUID().toString();
        api.transfer(token, account, destination, 10_000, key);

        MvcTestResult different = api.transfer(token, account, destination, 20_000, key);

        assertThat(different).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(different).bodyJson().extractingPath("$.title").isEqualTo("Idempotency-Key reutilizada");
        assertThat(api.balance(token, account)).isEqualTo(90_000);
    }

    @Test
    void sameKeyOnAnotherEndpointIsRejected() {
        String key = UUID.randomUUID().toString();
        api.movement("/api/deposits", token, account, 1_000, key);

        assertThat(api.movement("/api/withdrawals", token, account, 1_000, key))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void failedOperationDoesNotConsumeTheKey() {
        String key = UUID.randomUUID().toString();

        // Falla por saldo insuficiente: no se guarda nada.
        assertThat(api.transfer(token, account, destination, 150_000, key))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);

        // Con saldo suficiente, la misma clave y el mismo cuerpo ahora sí se ejecutan.
        api.deposit(token, account, 50_000);
        assertThat(api.transfer(token, account, destination, 150_000, key))
                .hasStatus(HttpStatus.CREATED)
                .hasHeader(IdempotentResponse.REPLAYED_HEADER, "false");
    }

    @Test
    void concurrentRequestsWithSameKeyExecuteExactlyOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcTestResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<MvcTestResult> call = () -> {
                    start.await();
                    return api.transfer(token, account, destination, 1_000, key);
                };
                futures.add(executor.submit(call));
            }
            start.countDown(); // todas las peticiones salen al mismo tiempo

            for (Future<MvcTestResult> future : futures) {
                assertThat(future.get()).hasStatus(HttpStatus.CREATED);
            }
        } finally {
            executor.shutdownNow();
        }

        Integer transfers = jdbc.queryForObject("""
                SELECT COUNT(*) FROM transactions WHERE idempotency_key = ? AND type = 'TRANSFER'""",
                Integer.class, key);
        assertThat(transfers).isEqualTo(1);
        assertThat(api.balance(token, account)).isEqualTo(99_000);
    }
}
