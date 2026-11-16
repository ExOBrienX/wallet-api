package cl.exobrienx.wallet.auth;

import cl.exobrienx.wallet.TestcontainersConfiguration;
import cl.exobrienx.wallet.support.TestData;
import cl.exobrienx.wallet.user.NewUser;
import cl.exobrienx.wallet.user.Role;
import com.jayway.jsonpath.JsonPath;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthIT {

    private static final String PASSWORD = "secreta123";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AuthService authService;

    // --- registro --------------------------------------------------------------

    @Test
    void registerCreatesUserWithoutExposingPassword() {
        String email = uniqueEmail();

        MvcTestResult result = post("/api/auth/register", TestData.registerJson(email, PASSWORD, TestData.randomRut()));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.email").isEqualTo(email);
        assertThat(result).bodyJson().extractingPath("$.role").isEqualTo("USER");
        assertThat(result).bodyJson().doesNotHavePath("$.password");
        assertThat(result).bodyJson().doesNotHavePath("$.passwordHash");
    }

    @Test
    void registerRejectsDuplicateEmailIgnoringCase() {
        String email = uniqueEmail();
        post("/api/auth/register", TestData.registerJson(email, PASSWORD, TestData.randomRut()));

        MvcTestResult result = post("/api/auth/register", TestData.registerJson(email.toUpperCase(), PASSWORD, TestData.randomRut()));

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Correo ya registrado");
    }

    @Test
    void registerValidatesInputWithFieldErrors() {
        MvcTestResult result = post("/api/auth/register", TestData.registerJson("no-es-correo", "corta", "12345678-9"));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().hasPath("$.errors.email");
        assertThat(result).bodyJson().hasPath("$.errors.password");
        assertThat(result).bodyJson().hasPath("$.errors.rut");
    }

    @Test
    void registerStoresProfileAndNormalizesRut() {
        String rut = TestData.randomRut();
        String formatted = rut.substring(0, 2) + "." + rut.substring(2, 5) + "." + rut.substring(5).toLowerCase();

        MvcTestResult result = post("/api/auth/register", TestData.registerJson(uniqueEmail(), PASSWORD, formatted));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.rut").isEqualTo(rut);
        assertThat(result).bodyJson().extractingPath("$.firstName").isEqualTo("Ana");
        assertThat(result).bodyJson().extractingPath("$.lastName").isEqualTo("Pérez Soto");
        assertThat(result).bodyJson().extractingPath("$.phone").isEqualTo("+56911112222");
    }

    @Test
    void registerRejectsDuplicateRut() {
        String rut = TestData.randomRut();
        post("/api/auth/register", TestData.registerJson(uniqueEmail(), PASSWORD, rut));

        MvcTestResult result = post("/api/auth/register", TestData.registerJson(uniqueEmail(), PASSWORD, rut));

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("RUT ya registrado");
    }

    @Test
    void malformedJsonReturnsProblemDetail() {
        MvcTestResult result = post("/api/auth/register", "{ no es json");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // --- login -----------------------------------------------------------------

    @Test
    void loginReturnsBearerTokens() {
        String email = registeredUser();

        MvcTestResult result = post("/api/auth/login", credentials(email, PASSWORD));

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.tokenType").isEqualTo("Bearer");
        assertThat(result).bodyJson().extractingPath("$.expiresIn").isEqualTo(900);
        assertThat(result).bodyJson().hasPath("$.accessToken");
        assertThat(result).bodyJson().hasPath("$.refreshToken");
    }

    @Test
    void loginWithWrongPasswordOrUnknownEmailGivesSameAnswer() throws Exception {
        String email = registeredUser();

        MvcTestResult wrongPassword = post("/api/auth/login", credentials(email, "incorrecta"));
        MvcTestResult unknownEmail = post("/api/auth/login", credentials(uniqueEmail(), PASSWORD));

        assertThat(wrongPassword).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getResponse().getContentAsString())
                .isEqualTo(unknownEmail.getResponse().getContentAsString());
    }

    // --- refresh y logout ------------------------------------------------------

    @Test
    void refreshRotatesTokenAndOldOneStopsWorking() {
        Tokens first = login(registeredUser());

        MvcTestResult refreshed = post("/api/auth/refresh", refreshBody(first.refresh()));
        assertThat(refreshed).hasStatusOk();
        Tokens second = Tokens.from(refreshed);
        assertThat(second.refresh()).isNotEqualTo(first.refresh());

        assertThat(post("/api/auth/refresh", refreshBody(first.refresh())))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void reusingRevokedRefreshTokenRevokesEverySession() {
        Tokens first = login(registeredUser());
        Tokens second = Tokens.from(post("/api/auth/refresh", refreshBody(first.refresh())));

        // Alguien reutiliza el token ya rotado: se asume robo.
        assertThat(post("/api/auth/refresh", refreshBody(first.refresh())))
                .hasStatus(HttpStatus.UNAUTHORIZED);

        // El token legítimo más reciente también quedó revocado.
        assertThat(post("/api/auth/refresh", refreshBody(second.refresh())))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutRevokesRefreshToken() {
        Tokens tokens = login(registeredUser());

        MvcTestResult logout = mvc.post().uri("/api/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(tokens.refresh()))
                .exchange();

        assertThat(logout).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(post("/api/auth/refresh", refreshBody(tokens.refresh())))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutRequiresAuthentication() {
        MvcTestResult result = post("/api/auth/logout", refreshBody("cualquiera"));

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // --- autorización por token y rol ------------------------------------------

    @Test
    void tamperedAccessTokenIsRejected() {
        Tokens tokens = login(registeredUser());
        String tampered = tokens.access().substring(0, tokens.access().length() - 4) + "AAAA";

        MvcTestResult result = mvc.get().uri("/api/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).bodyJson().extractingPath("$.detail")
                .isEqualTo("El token de acceso no es válido o expiró.");
    }

    @Test
    void userCannotAccessAdminRoutes() {
        Tokens tokens = login(registeredUser());

        MvcTestResult result = mvc.get().uri("/api/admin/reconciliation")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.access())
                .exchange();

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void adminCanAccessAdminRoutes() {
        String email = uniqueEmail();
        authService.createUser(new NewUser(email, PASSWORD, "Admin", "Test", null, null, Role.ADMIN));
        Tokens tokens = login(email);

        MvcTestResult result = mvc.get().uri("/api/admin/reconciliation")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.access())
                .exchange();

        assertThat(result).hasStatusOk();
    }

    // --- helpers ---------------------------------------------------------------

    private MvcTestResult post(String uri, String json) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private String registeredUser() {
        String email = uniqueEmail();
        assertThat(post("/api/auth/register", TestData.registerJson(email, PASSWORD, TestData.randomRut()))).hasStatus(HttpStatus.CREATED);
        return email;
    }

    private Tokens login(String email) {
        MvcTestResult result = post("/api/auth/login", credentials(email, PASSWORD));
        assertThat(result).hasStatusOk();
        return Tokens.from(result);
    }

    private static String uniqueEmail() {
        return TestData.uniqueEmail();
    }

    private static String credentials(String email, String password) {
        return """
                {"email": "%s", "password": "%s"}""".formatted(email, password);
    }

    private static String refreshBody(String refreshToken) {
        return """
                {"refreshToken": "%s"}""".formatted(refreshToken);
    }

    private record Tokens(String access, String refresh) {
        static Tokens from(MvcTestResult result) {
            try {
                String body = result.getResponse().getContentAsString();
                return new Tokens(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
