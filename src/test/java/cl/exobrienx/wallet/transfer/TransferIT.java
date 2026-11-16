package cl.exobrienx.wallet.transfer;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TransferIT {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private String ana;
    private String matias;
    private UUID anaAccount;
    private UUID matiasAccount;
    private String matiasNumber;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        ana = api.newClientToken();
        matias = api.newClientToken();
        anaAccount = api.openAccount(ana);
        matiasAccount = api.openAccount(matias);
        matiasNumber = api.accountNumber(matias, matiasAccount);
        api.deposit(ana, anaAccount, 100_000);
    }

    @Test
    void transferMovesMoneyBetweenAccounts() {
        MvcTestResult result = api.transfer(ana, anaAccount, matiasNumber, 30_000);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");
        assertThat(result).bodyJson().extractingPath("$.destinationAccountNumber").isEqualTo(matiasNumber);
        assertThat(result).bodyJson().extractingPath("$.recipientName").isEqualTo("Ana P.");
        assertThat(result).bodyJson().extractingPath("$.balanceAfter").isEqualTo(70_000);
        assertThat(api.balance(ana, anaAccount)).isEqualTo(70_000);
        assertThat(api.balance(matias, matiasAccount)).isEqualTo(30_000);
    }

    @Test
    void transferBetweenOwnAccountsIsAllowed() {
        UUID savings = api.openAccount(ana);

        assertThat(api.transfer(ana, anaAccount, api.accountNumber(ana, savings), 10_000))
                .hasStatus(HttpStatus.CREATED);
        assertThat(api.balance(ana, savings)).isEqualTo(10_000);
    }

    @Test
    void transferIsRecordedAsSingleBalancedTransaction() {
        UUID transactionId = UUID.fromString(ApiClient.read(api.transfer(ana, anaAccount, matiasNumber, 5_000),
                "$.transactionId"));

        Long net = jdbc.queryForObject("""
                SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END)
                FROM ledger_entries WHERE transaction_id = ?""", Long.class, transactionId);
        Integer entries = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = ?",
                Integer.class, transactionId);
        assertThat(net).isZero();
        assertThat(entries).isEqualTo(2);
    }

    @Test
    void insufficientFundsLeavesBothBalancesUnchanged() {
        MvcTestResult result = api.transfer(ana, anaAccount, matiasNumber, 100_001);

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Saldo insuficiente");
        assertThat(api.balance(ana, anaAccount)).isEqualTo(100_000);
        assertThat(api.balance(matias, matiasAccount)).isZero();
    }

    @Test
    void cannotTransferToSameAccount() {
        MvcTestResult result = api.transfer(ana, anaAccount, api.accountNumber(ana, anaAccount), 1_000);

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Transferencia inválida");
    }

    @Test
    void cannotTransferFromAccountOfAnotherUser() {
        api.deposit(matias, matiasAccount, 50_000);

        // Ana intenta usar la cuenta de Matías como origen.
        assertThat(api.transfer(ana, matiasAccount, api.accountNumber(ana, anaAccount), 1_000))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.balance(matias, matiasAccount)).isEqualTo(50_000);
    }

    @Test
    void blockedAccountsCannotSendOrReceive() {
        jdbc.update("UPDATE accounts SET status = 'BLOCKED' WHERE id = ?", matiasAccount);
        assertThat(api.transfer(ana, anaAccount, matiasNumber, 1_000)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.title").isEqualTo("Cuenta de destino no disponible");

        jdbc.update("UPDATE accounts SET status = 'ACTIVE' WHERE id = ?", matiasAccount);
        jdbc.update("UPDATE accounts SET status = 'BLOCKED' WHERE id = ?", anaAccount);
        assertThat(api.transfer(ana, anaAccount, matiasNumber, 1_000)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.title").isEqualTo("Cuenta bloqueada");
    }

    @Test
    void unknownDestinationReturnsNotFound() {
        // Número con dígito verificador válido que no corresponde a ninguna cuenta.
        String unused = "7992739875".equals(matiasNumber) ? "1234567897" : "7992739875";

        assertThat(api.transfer(ana, anaAccount, unused, 1_000)).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.title").isEqualTo("Cuenta de destino no encontrada");
    }

    @Test
    void systemAccountCannotReceiveTransfers() {
        assertThat(api.transfer(ana, anaAccount, "0000000000", 1_000)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void typoInAccountNumberIsDetectedByCheckDigit() {
        char last = matiasNumber.charAt(9);
        String typo = matiasNumber.substring(0, 9) + (last == '9' ? '0' : (char) (last + 1));

        assertThat(api.transfer(ana, anaAccount, typo, 1_000)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.title").isEqualTo("Número de cuenta inválido");
    }

    @Test
    void dailyLimitIsEnforcedPerSourceAccount() {
        api.deposit(ana, anaAccount, 2_000_000);

        assertThat(api.transfer(ana, anaAccount, matiasNumber, 600_000)).hasStatus(HttpStatus.CREATED);
        assertThat(api.transfer(ana, anaAccount, matiasNumber, 400_000)).hasStatus(HttpStatus.CREATED);
        MvcTestResult exceeded = api.transfer(ana, anaAccount, matiasNumber, 1);

        assertThat(exceeded).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(exceeded).bodyJson().extractingPath("$.title").isEqualTo("Límite diario excedido");

        // Otra cuenta del mismo usuario tiene su propio límite.
        UUID second = api.openAccount(ana);
        api.deposit(ana, second, 10_000);
        assertThat(api.transfer(ana, second, matiasNumber, 10_000)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void depositsAndWithdrawalsDoNotCountTowardsDailyLimit() {
        api.deposit(ana, anaAccount, 3_000_000);
        api.withdraw(ana, anaAccount, 1_500_000);

        assertThat(api.transfer(ana, anaAccount, matiasNumber, 1_000_000)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void invalidRequestReturnsFieldErrors() {
        MvcTestResult result = api.transfer(ana, anaAccount, "123", 0);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().hasPath("$.errors.destinationAccountNumber");
        assertThat(result).bodyJson().hasPath("$.errors.amount");
    }
}
