package cl.exobrienx.wallet.common;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/** Comprobaciones de seguridad transversales a toda la API. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SecurityIT {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AuthService authService;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
    }

    // --- fuerza bruta en el login -----------------------------------------------------

    @Test
    void loginIsBlockedAfterRepeatedFailuresEvenWithCorrectPassword() {
        String email = registeredClient();
        for (int i = 0; i < 5; i++) {
            assertThat(login(email, "incorrecta", "203.0.113.10")).hasStatus(HttpStatus.UNAUTHORIZED);
        }

        MvcTestResult blocked = login(email, ApiClient.PASSWORD, "203.0.113.10");

        assertThat(blocked).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked).headers().containsHeader(HttpHeaders.RETRY_AFTER);
        assertThat(blocked).bodyJson().extractingPath("$.title").isEqualTo("Demasiados intentos");
    }

    @Test
    void blockDoesNotAffectSameEmailFromAnotherAddress() {
        String email = registeredClient();
        for (int i = 0; i < 5; i++) {
            login(email, "incorrecta", "203.0.113.20");
        }

        assertThat(login(email, ApiClient.PASSWORD, "198.51.100.30")).hasStatusOk();
    }

    // --- health -----------------------------------------------------------------------

    @Test
    void healthDetailsAreOnlyVisibleToAdmins() {
        MvcTestResult anonymous = mvc.get().uri("/actuator/health").exchange();
        MvcTestResult client = api.get("/actuator/health", api.newClientToken());
        MvcTestResult admin = api.get("/actuator/health", adminToken());

        assertThat(anonymous).hasStatusOk().bodyJson().doesNotHavePath("$.components");
        assertThat(client).hasStatusOk().bodyJson().doesNotHavePath("$.components");
        assertThat(admin).hasStatusOk().bodyJson().hasPath("$.components.db");
    }

    @Test
    void livenessAndReadinessProbesArePublic() {
        assertThat(mvc.get().uri("/actuator/health/liveness").exchange()).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("UP");
        assertThat(mvc.get().uri("/actuator/health/readiness").exchange()).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("UP");
    }

    @Test
    void readinessIncludesLedgerCheckForAdmins() {
        MvcTestResult result = api.get("/actuator/health/readiness", adminToken());

        assertThat(result).bodyJson().extractingPath("$.components.ledger.status").isEqualTo("UP");
        assertThat(result).bodyJson().extractingPath("$.components.ledger.details.systemAccount").isEqualTo("presente");
        assertThat(result).bodyJson().extractingPath("$.components.ledger.details.protectionTriggers")
                .isEqualTo("instalados");
        assertThat(result).bodyJson().extractingPath("$.components.db.status").isEqualTo("UP");
    }

    // --- CORS -------------------------------------------------------------------------

    @Test
    void corsIsClosedByDefault() {
        // Sin orígenes configurados, un navegador no puede llamar a la API desde otro sitio:
        // la respuesta no incluye Access-Control-Allow-Origin.
        MvcTestResult preflight = mvc.options().uri("/api/transfers")
                .header(HttpHeaders.ORIGIN, "https://sitio-malicioso.com")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange();

        assertThat(preflight).doesNotContainHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(preflight.getResponse().getStatus()).isEqualTo(403);
    }

    // --- headers y errores --------------------------------------------------------------

    @Test
    void responsesIncludeSecurityHeaders() {
        MvcTestResult result = api.get("/api/accounts", api.newClientToken());

        assertThat(result).hasHeader("X-Content-Type-Options", "nosniff");
        assertThat(result).hasHeader("X-Frame-Options", "DENY");
        assertThat(result).hasHeader("Referrer-Policy", "no-referrer");
        assertThat(result).headers().containsHeader(HttpHeaders.CACHE_CONTROL);
    }

    @Test
    void malformedIdentifiersReturnBadRequestWithoutInternalDetails() throws Exception {
        MvcTestResult result = api.get("/api/accounts/no-es-un-uuid", api.newClientToken());

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Exception", "java.", "at cl.");
    }

    @Test
    void tokenWithForgedRoleIsRejected() {
        // Un token firmado con otra clave (por ejemplo, fabricado con rol ADMIN) no se acepta.
        String forged = "eyJhbGciOiJIUzI1NiJ9."
                + "eyJpc3MiOiJ3YWxsZXQtYXBpIiwic3ViIjoiMDAwMDAwMDAtMDAwMC0wMDAwLTAwMDAtMDAwMDAwMDAwMDAxIiwicm9sZXMiOlsiQURNSU4iXSwiZXhwIjo0MTAyNDQ0ODAwfQ."
                + "c2lnbmF0dXJhLWZhbHNh";

        assertThat(api.get("/api/admin/reconciliation", forged)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unsignedTokenIsRejected() {
        // Algoritmo "none": un ataque clásico contra validadores de JWT mal configurados.
        String unsigned = "eyJhbGciOiJub25lIn0."
                + "eyJpc3MiOiJ3YWxsZXQtYXBpIiwic3ViIjoiMDAwMDAwMDAtMDAwMC0wMDAwLTAwMDAtMDAwMDAwMDAwMDAxIiwicm9sZXMiOlsiQURNSU4iXSwiZXhwIjo0MTAyNDQ0ODAwfQ.";

        assertThat(api.get("/api/admin/reconciliation", unsigned)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // --- helpers ------------------------------------------------------------------------

    private String registeredClient() {
        String email = TestData.uniqueEmail();
        api.post("/api/auth/register", TestData.registerJson(email, ApiClient.PASSWORD, TestData.randomRut()), null);
        return email;
    }

    private String adminToken() {
        String email = TestData.uniqueEmail();
        authService.createUser(new NewUser(email, ApiClient.PASSWORD, "Admin", "Test", null, null, Role.ADMIN));
        return api.login(email);
    }

    private MvcTestResult login(String email, String password, String clientIp) {
        return mvc.post().uri("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}""".formatted(email, password))
                .exchange();
    }
}
