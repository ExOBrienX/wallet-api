package cl.exobrienx.wallet.account;

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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AccountHistoryIT {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AuthService authService;

    private ApiClient api;
    private String ana;
    private UUID anaAccount;
    private String anaNumber;
    private String matias;
    private UUID matiasAccount;
    private String matiasNumber;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        ana = api.newClientToken();
        anaAccount = api.openAccount(ana);
        anaNumber = api.accountNumber(ana, anaAccount);
        matias = api.newClientToken();
        matiasAccount = api.openAccount(matias);
        matiasNumber = api.accountNumber(matias, matiasAccount);
    }

    @Test
    void historyListsMovementsNewestFirstWithSignedAmounts() {
        api.deposit(ana, anaAccount, 50_000);
        api.withdraw(ana, anaAccount, 8_000);
        api.transfer(ana, anaAccount, matiasNumber, 12_000);

        MvcTestResult result = history(ana, anaAccount, "");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.totalElements").isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$.content[*].type").asArray()
                .containsExactly("TRANSFER", "WITHDRAWAL", "DEPOSIT");
        assertThat(result).bodyJson().extractingPath("$.content[*].amount").asArray()
                .containsExactly(-12_000, -8_000, 50_000);
        // La contraparte solo aparece en transferencias; en depósitos y retiros es el sistema.
        assertThat(result).bodyJson().extractingPath("$.content[0].counterpartyAccountNumber").isEqualTo(matiasNumber);
        assertThat(result).bodyJson().extractingPath("$.content[2].counterpartyAccountNumber").isNull();
        assertThat(result).bodyJson().extractingPath("$.content[0].description").isEqualTo("Test");
    }

    @Test
    void recipientSeesIncomingTransfer() {
        api.deposit(ana, anaAccount, 10_000);
        api.transfer(ana, anaAccount, matiasNumber, 4_000);

        MvcTestResult result = history(matias, matiasAccount, "");

        assertThat(result).bodyJson().extractingPath("$.content[0].amount").isEqualTo(4_000);
        assertThat(result).bodyJson().extractingPath("$.content[0].counterpartyAccountNumber").isEqualTo(anaNumber);
    }

    @Test
    void historyIsPaginated() {
        for (int i = 0; i < 25; i++) {
            api.deposit(ana, anaAccount, 1_000 + i);
        }

        MvcTestResult first = history(ana, anaAccount, "?page=0&size=10");
        MvcTestResult last = history(ana, anaAccount, "?page=2&size=10");

        assertThat(first).bodyJson().extractingPath("$.content.length()").isEqualTo(10);
        assertThat(first).bodyJson().extractingPath("$.totalElements").isEqualTo(25);
        assertThat(first).bodyJson().extractingPath("$.totalPages").isEqualTo(3);
        assertThat(first).bodyJson().extractingPath("$.content[0].amount").isEqualTo(1_024);
        assertThat(last).bodyJson().extractingPath("$.content.length()").isEqualTo(5);
        assertThat(last).bodyJson().extractingPath("$.content[4].amount").isEqualTo(1_000);
    }

    @Test
    void pageSizeIsCappedAt100() {
        api.deposit(ana, anaAccount, 1_000);

        assertThat(history(ana, anaAccount, "?size=5000")).bodyJson().extractingPath("$.size").isEqualTo(100);
    }

    @Test
    void historyCanBeFilteredByDate() {
        api.deposit(ana, anaAccount, 1_000);

        assertThat(history(ana, anaAccount, "?from=2000-01-01T00:00:00Z&to=2999-01-01T00:00:00Z"))
                .bodyJson().extractingPath("$.totalElements").isEqualTo(1);
        assertThat(history(ana, anaAccount, "?from=2999-01-01T00:00:00Z"))
                .bodyJson().extractingPath("$.totalElements").isEqualTo(0);
        assertThat(history(ana, anaAccount, "?to=2000-01-01T00:00:00Z"))
                .bodyJson().extractingPath("$.totalElements").isEqualTo(0);
    }

    @Test
    void invalidDateRangeIsRejected() {
        assertThat(history(ana, anaAccount, "?from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.title").isEqualTo("Rango de fechas inválido");
        assertThat(history(ana, anaAccount, "?from=ayer")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void clientCannotSeeHistoryOfAnotherAccountButAdminCan() {
        api.deposit(matias, matiasAccount, 3_000);
        String email = TestData.uniqueEmail();
        authService.createUser(new NewUser(email, ApiClient.PASSWORD, "Admin", "Test", null, null, Role.ADMIN));

        assertThat(history(ana, matiasAccount, "")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(history(api.login(email), matiasAccount, "")).hasStatusOk()
                .bodyJson().extractingPath("$.totalElements").isEqualTo(1);
    }

    @Test
    void reversalAppearsInHistoryAndOriginalIsMarked() {
        UUID deposit = UUID.fromString(ApiClient.read(api.deposit(ana, anaAccount, 7_000), "$.transactionId"));
        String email = TestData.uniqueEmail();
        authService.createUser(new NewUser(email, ApiClient.PASSWORD, "Admin", "Test", null, null, Role.ADMIN));
        api.post("/api/admin/transactions/" + deposit + "/reverse", "{}", api.login(email));

        MvcTestResult result = history(ana, anaAccount, "");

        assertThat(result).bodyJson().extractingPath("$.content[*].type").asArray()
                .containsExactly("REVERSAL", "DEPOSIT");
        assertThat(result).bodyJson().extractingPath("$.content[0].amount").isEqualTo(-7_000);
        assertThat(result).bodyJson().extractingPath("$.content[1].status").isEqualTo("REVERSED");
    }

    private MvcTestResult history(String token, UUID accountId, String query) {
        return api.get("/api/accounts/" + accountId + "/transactions" + query, token);
    }
}
