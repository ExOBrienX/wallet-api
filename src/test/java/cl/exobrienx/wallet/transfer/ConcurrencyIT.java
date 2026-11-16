package cl.exobrienx.wallet.transfer;

import cl.exobrienx.wallet.TestcontainersConfiguration;
import cl.exobrienx.wallet.ledger.ReconciliationReport;
import cl.exobrienx.wallet.ledger.ReconciliationService;
import cl.exobrienx.wallet.support.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de concurrencia: muchas peticiones simultáneas contra las mismas cuentas, pasando por
 * toda la aplicación (HTTP, seguridad, idempotencia, servicio y PostgreSQL real).
 * <p>
 * Cada escenario apunta a un riesgo distinto:
 * <ul>
 *   <li>carrera por el saldo: dos retiros leen el mismo saldo y ambos pasan la validación;</li>
 *   <li>deadlock: dos transferencias cruzadas se bloquean mutuamente;</li>
 *   <li>carga mixta: el dinero total del sistema cambia o el libro deja de cuadrar.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class ConcurrencyIT {

    private static final int CREATED = 201;
    private static final int UNPROCESSABLE = 422;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReconciliationService reconciliationService;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
    }

    @Test
    void concurrentTransfersNeverOverdrawAnAccount() throws Exception {
        TestAccount source = newAccountWithBalance(5_000);
        TestAccount destination = newAccountWithBalance(0);

        // 100 transferencias de $1.000 desde una cuenta que solo tiene para 5.
        // El saldo alcanza para menos operaciones que conexiones simultáneas a la base: si el
        // bloqueo fallara, más de 5 transacciones leerían el mismo saldo a la vez y el test lo
        // detectaría. Con un saldo igual o mayor que el pool, el error podría pasar inadvertido.
        List<Integer> statuses = runConcurrently(100,
                i -> api.transfer(source.token(), source.id(), destination.number(), 1_000));

        Map<Integer, Long> byStatus = countByStatus(statuses);
        assertThat(byStatus).containsOnlyKeys(CREATED, UNPROCESSABLE);
        assertThat(byStatus.get(CREATED)).isEqualTo(5);
        assertThat(api.balance(source.token(), source.id())).isZero();
        assertThat(api.balance(destination.token(), destination.id())).isEqualTo(5_000);
    }

    @Test
    void concurrentWithdrawalsNeverOverdrawAnAccount() throws Exception {
        TestAccount account = newAccountWithBalance(5_000);

        List<Integer> statuses = runConcurrently(100, i -> api.withdraw(account.token(), account.id(), 1_000));

        assertThat(countByStatus(statuses).get(CREATED)).isEqualTo(5);
        assertThat(api.balance(account.token(), account.id())).isZero();
    }

    @Test
    void opposingTransfersBetweenSameAccountsDoNotDeadlock() throws Exception {
        TestAccount a = newAccountWithBalance(500_000);
        TestAccount b = newAccountWithBalance(500_000);

        // La mitad de los hilos transfiere de A a B y la otra mitad de B a A, todos a la vez.
        // Sin un orden de bloqueo fijo, PostgreSQL detectaría deadlocks y algunas peticiones
        // terminarían en error 500.
        List<Integer> statuses = runConcurrently(100, i -> i % 2 == 0
                ? api.transfer(a.token(), a.id(), b.number(), 1_000)
                : api.transfer(b.token(), b.id(), a.number(), 1_000));

        assertThat(statuses).allMatch(status -> status == CREATED);
        assertThat(api.balance(a.token(), a.id())).isEqualTo(500_000);
        assertThat(api.balance(b.token(), b.id())).isEqualTo(500_000);
    }

    @Test
    void randomTransfersPreserveTotalMoneyAndKeepLedgerBalanced() throws Exception {
        int accountCount = 12;
        long initialBalance = 100_000;
        List<TestAccount> accounts = new ArrayList<>();
        for (int i = 0; i < accountCount; i++) {
            accounts.add(newAccountWithBalance(initialBalance));
        }
        long totalBefore = accountCount * initialBalance;

        // 120 hilos, cada uno con 5 transferencias entre cuentas al azar (600 en total).
        int threads = 120;
        int transfersPerThread = 5;
        List<Integer> statuses = runConcurrently(threads, i -> {
            MvcTestResult last = null;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int t = 0; t < transfersPerThread; t++) {
                TestAccount from = accounts.get(random.nextInt(accountCount));
                TestAccount to = accounts.get(random.nextInt(accountCount));
                while (to == from) {
                    to = accounts.get(random.nextInt(accountCount));
                }
                last = api.transfer(from.token(), from.id(), to.number(), random.nextLong(1_000, 20_001));
                // Saldo insuficiente es un resultado esperado; cualquier otro error no lo es.
                int status = last.getResponse().getStatus();
                if (status != CREATED && status != UNPROCESSABLE) {
                    return last;
                }
            }
            return last;
        });

        assertThat(statuses).allMatch(status -> status == CREATED || status == UNPROCESSABLE);

        List<UUID> ids = accounts.stream().map(TestAccount::id).toList();
        Map<UUID, Long> balances = balancesOf(ids);
        assertThat(balances.values()).allMatch(balance -> balance >= 0);
        assertThat(balances.values().stream().mapToLong(Long::longValue).sum())
                .as("el dinero total entre las cuentas no cambia")
                .isEqualTo(totalBefore);

        ReconciliationReport report = reconciliationService.reconcile();
        assertThat(report.balanced()).isTrue();
        assertThat(report.difference()).isZero();
        assertThat(report.unbalancedTransactions()).isEmpty();
        assertThat(report.negativeUserAccounts())
                .extracting(ReconciliationReport.NegativeAccount::accountId)
                .doesNotContainAnyElementsOf(ids);
    }

    // --- helpers -------------------------------------------------------------------

    private record TestAccount(String token, UUID id, String number) {
    }

    private TestAccount newAccountWithBalance(long balance) {
        String token = api.newClientToken();
        UUID id = api.openAccount(token);
        if (balance > 0) {
            assertThat(api.deposit(token, id, balance).getResponse().getStatus()).isEqualTo(CREATED);
        }
        return new TestAccount(token, id, api.accountNumber(token, id));
    }

    /**
     * Ejecuta la tarea en {@code threads} hilos que arrancan al mismo tiempo (todos esperan la
     * misma señal) y devuelve el código HTTP de cada uno.
     */
    private static List<Integer> runConcurrently(int threads, IntFunction<MvcTestResult> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.apply(index).getResponse().getStatus();
                }));
            }
            ready.await();
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }

    private static Map<Integer, Long> countByStatus(List<Integer> statuses) {
        return statuses.stream().collect(Collectors.groupingBy(status -> status, Collectors.counting()));
    }

    private Map<UUID, Long> balancesOf(List<UUID> ids) {
        Map<UUID, Long> balances = new HashMap<>();
        for (UUID id : ids) {
            Long balance = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
                    FROM ledger_entries WHERE account_id = ?""", Long.class, id);
            balances.put(id, balance);
        }
        return balances;
    }
}
