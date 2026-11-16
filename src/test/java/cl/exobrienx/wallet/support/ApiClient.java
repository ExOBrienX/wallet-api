package cl.exobrienx.wallet.support;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.io.UnsupportedEncodingException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Atajos para los tests de integración: registrar clientes, iniciar sesión y llamar a la
 * API con un token, sin repetir el armado de cada petición.
 */
public class ApiClient {

    public static final String PASSWORD = "secreta123";

    private final MockMvcTester mvc;

    public ApiClient(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    /** Registra un cliente nuevo y devuelve su token de acceso. */
    public String newClientToken() {
        String email = TestData.uniqueEmail();
        assertThat(post("/api/auth/register", TestData.registerJson(email, PASSWORD, TestData.randomRut()), null))
                .hasStatus(HttpStatus.CREATED);
        return login(email);
    }

    public String login(String email) {
        MvcTestResult result = post("/api/auth/login", """
                {"email": "%s", "password": "%s"}""".formatted(email, PASSWORD), null);
        assertThat(result).hasStatusOk();
        return read(result, "$.accessToken");
    }

    /** Abre una cuenta para el dueño del token y devuelve su id. */
    public UUID openAccount(String token) {
        MvcTestResult result = post("/api/accounts", "{}", token);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(read(result, "$.id"));
    }

    public MvcTestResult deposit(String token, UUID accountId, long amount) {
        return movement("/api/deposits", token, accountId, amount, UUID.randomUUID().toString());
    }

    public MvcTestResult withdraw(String token, UUID accountId, long amount) {
        return movement("/api/withdrawals", token, accountId, amount, UUID.randomUUID().toString());
    }

    public MvcTestResult movement(String uri, String token, UUID accountId, long amount, String idempotencyKey) {
        return mvc.post().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountId": "%s", "amount": %d, "description": "Test"}""".formatted(accountId, amount))
                .exchange();
    }

    public MvcTestResult transfer(String token, UUID sourceAccountId, String destinationNumber, long amount) {
        return transfer(token, sourceAccountId, destinationNumber, amount, UUID.randomUUID().toString());
    }

    public MvcTestResult transfer(String token, UUID sourceAccountId, String destinationNumber, long amount,
                                  String idempotencyKey) {
        return mvc.post().uri("/api/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceAccountId": "%s", "destinationAccountNumber": "%s", "amount": %d,
                         "description": "Test"}""".formatted(sourceAccountId, destinationNumber, amount))
                .exchange();
    }

    public String accountNumber(String token, UUID accountId) {
        return read(get("/api/accounts/" + accountId, token), "$.accountNumber");
    }

    public long balance(String token, UUID accountId) {
        Number balance = read(get("/api/accounts/" + accountId, token), "$.balance");
        return balance.longValue();
    }

    public MvcTestResult get(String uri, String token) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    public MvcTestResult post(String uri, String json, String token) {
        var request = mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return request.exchange();
    }

    public static <T> T read(MvcTestResult result, String jsonPath) {
        try {
            return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
