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
import org.springframework.http.HttpHeaders;
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
class AccountIT {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AuthService authService;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
    }

    @Test
    void openAccountReturnsCreatedWithValidNumberAndZeroBalance() {
        String token = api.newClientToken();

        MvcTestResult result = api.post("/api/accounts", """
                {"alias": "Ahorro"}""", token);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).headers().containsHeader(HttpHeaders.LOCATION);
        assertThat(result).bodyJson().extractingPath("$.alias").isEqualTo("Ahorro");
        assertThat(result).bodyJson().extractingPath("$.currency").isEqualTo("CLP");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
        assertThat(result).bodyJson().extractingPath("$.balance").isEqualTo(0);
        String number = ApiClient.read(result, "$.accountNumber");
        assertThat(AccountNumberGenerator.isValid(number)).isTrue();
    }

    @Test
    void openAccountWithoutBody() {
        String token = api.newClientToken();

        assertThat(mvc.post().uri("/api/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange())
                .hasStatus(HttpStatus.CREATED);
    }

    @Test
    void listShowsOnlyOwnAccountsWithCalculatedBalances() {
        String ana = api.newClientToken();
        String other = api.newClientToken();
        UUID first = api.openAccount(ana);
        UUID second = api.openAccount(ana);
        api.openAccount(other);
        api.deposit(ana, first, 15_000);
        api.deposit(ana, first, 5_000);
        api.withdraw(ana, first, 2_000);

        MvcTestResult result = api.get("/api/accounts", ana);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.length()").isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$[0].id").isEqualTo(first.toString());
        assertThat(result).bodyJson().extractingPath("$[0].balance").isEqualTo(18_000);
        assertThat(result).bodyJson().extractingPath("$[1].id").isEqualTo(second.toString());
        assertThat(result).bodyJson().extractingPath("$[1].balance").isEqualTo(0);
    }

    @Test
    void cannotSeeAccountOfAnotherUser() {
        UUID othersAccount = api.openAccount(api.newClientToken());

        MvcTestResult result = api.get("/api/accounts/" + othersAccount, api.newClientToken());

        // 404 y no 403: no se revela que la cuenta existe.
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void adminCanSeeAnyAccountButCannotOpenOne() {
        String client = api.newClientToken();
        UUID account = api.openAccount(client);
        api.deposit(client, account, 7_000);
        String email = TestData.uniqueEmail();
        authService.createUser(new NewUser(email, ApiClient.PASSWORD, "Admin", "Test", null, null, Role.ADMIN));
        String admin = api.login(email);

        assertThat(api.get("/api/accounts/" + account, admin)).hasStatusOk()
                .bodyJson().extractingPath("$.balance").isEqualTo(7_000);
        assertThat(api.post("/api/accounts", "{}", admin)).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void accountLimitPerUserIsEnforced() {
        String token = api.newClientToken();
        for (int i = 0; i < 5; i++) {
            api.openAccount(token);
        }

        MvcTestResult result = api.post("/api/accounts", "{}", token);

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Límite de cuentas alcanzado");
    }

    @Test
    void aliasIsValidated() {
        MvcTestResult result = api.post("/api/accounts", """
                {"alias": "%s"}""".formatted("x".repeat(41)), api.newClientToken());

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().hasPath("$.errors.alias");
    }

    @Test
    void unknownAccountReturnsNotFound() {
        assertThat(api.get("/api/accounts/" + UUID.randomUUID(), api.newClientToken()))
                .hasStatus(HttpStatus.NOT_FOUND);
    }
}
