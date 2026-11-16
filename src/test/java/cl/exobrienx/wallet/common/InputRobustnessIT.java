package cl.exobrienx.wallet.common;

import cl.exobrienx.wallet.TestcontainersConfiguration;
import cl.exobrienx.wallet.support.ApiClient;
import cl.exobrienx.wallet.support.TestData;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entradas inesperadas en todos los endpoints que reciben datos: caracteres de control, textos
 * enormes, tipos equivocados, JSON roto, intentos de inyección. La regla es la misma para todas:
 * la API nunca responde 500, y todo error viene en formato ProblemDetail con un código y un
 * motivo, sin repetir el valor recibido ni exponer detalles internos.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InputRobustnessIT {

    private static final String NUL = "\\u0000";
    private static final String RTL_OVERRIDE = "\\u202E";
    private static final String LONG = "x".repeat(10_000);

    @Autowired
    private MockMvcTester mvc;

    private ApiClient api;
    private String token;
    private UUID account;
    private String destination;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        if (token == null) {
            token = api.newClientToken();
            account = api.openAccount(token);
            destination = api.accountNumber(token, api.openAccount(token));
            api.deposit(token, account, 1_000_000);
        }
    }

    // --- registro -------------------------------------------------------------------

    Stream<Arguments> invalidRegistrations() {
        return Stream.of(
                registration("nombre con carácter nulo", "\"firstName\": \"Ana" + NUL + "\""),
                registration("nombre con números", "\"firstName\": \"Ana123\""),
                registration("nombre con emoji", "\"firstName\": \"Ana 😀\""),
                registration("nombre con HTML", "\"firstName\": \"<script>alert(1)</script>\""),
                registration("nombre con inversión de texto", "\"firstName\": \"Ana" + RTL_OVERRIDE + "\""),
                registration("nombre de 10.000 caracteres", "\"firstName\": \"" + LONG + "\""),
                registration("nombre solo espacios", "\"firstName\": \"   \""),
                registration("nombre numérico", "\"firstName\": 123"),
                registration("nombre como objeto", "\"firstName\": {\"a\": 1}"),
                registration("correo con carácter nulo", "\"email\": \"ana" + NUL + "@correo.cl\""),
                registration("correo con inyección SQL", "\"email\": \"x' OR '1'='1\""),
                registration("correo de 10.000 caracteres", "\"email\": \"" + LONG + "@correo.cl\""),
                registration("RUT con letras", "\"rut\": \"abc\""),
                registration("RUT con dígito incorrecto", "\"rut\": \"12.345.678-9\""),
                registration("RUT con inyección SQL", "\"rut\": \"1'; DROP TABLE users; --\""),
                registration("teléfono con letras", "\"phone\": \"+569abcdefgh\""),
                registration("contraseña corta", "\"password\": \"123\""),
                registration("contraseña de 73 bytes", "\"password\": \"" + "ñ".repeat(37) + "\""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRegistrations")
    void invalidRegistrationIsRejectedWithFieldErrors(String json) {
        MvcTestResult result = post("/api/auth/register", json, null);

        assertControlledError(result, 400);
    }

    @Test
    void namesWithAccentsApostrophesAndHyphensAreAccepted() {
        String json = TestData.registerJson(TestData.uniqueEmail(), ApiClient.PASSWORD, TestData.randomRut())
                .replace("\"Ana\"", "\"María-José\"").replace("\"Pérez Soto\"", "\"O'Higgins Núñez\"");

        assertThat(post("/api/auth/register", json, null).getResponse().getStatus()).isEqualTo(201);
    }

    // --- forma del cuerpo -------------------------------------------------------------

    Stream<Arguments> malformedBodies() {
        return Stream.of(
                Arguments.of(Named.of("cuerpo vacío", "")),
                Arguments.of(Named.of("JSON truncado", "{\"accountId\": ")),
                Arguments.of(Named.of("arreglo en vez de objeto", "[]")),
                Arguments.of(Named.of("null", "null")),
                Arguments.of(Named.of("texto plano", "hola")),
                Arguments.of(Named.of("número", "42")),
                Arguments.of(Named.of("monto como texto", "{\"accountId\": \"%s\", \"amount\": \"mil\"}")),
                Arguments.of(Named.of("monto con decimales", "{\"accountId\": \"%s\", \"amount\": 1000.5}")),
                Arguments.of(Named.of("monto en notación científica", "{\"accountId\": \"%s\", \"amount\": 1e3}")),
                Arguments.of(Named.of("monto que desborda un long", "{\"accountId\": \"%s\", \"amount\": 99999999999999999999999}")),
                Arguments.of(Named.of("monto booleano", "{\"accountId\": \"%s\", \"amount\": true}")),
                Arguments.of(Named.of("monto como objeto", "{\"accountId\": \"%s\", \"amount\": {\"valor\": 1}}")),
                Arguments.of(Named.of("cuenta que no es UUID", "{\"accountId\": \"no-es-uuid\", \"amount\": 1000}")),
                Arguments.of(Named.of("cuenta numérica", "{\"accountId\": 123, \"amount\": 1000}")),
                Arguments.of(Named.of("monto negativo", "{\"accountId\": \"%s\", \"amount\": -1000}")),
                Arguments.of(Named.of("monto cero", "{\"accountId\": \"%s\", \"amount\": 0}")),
                Arguments.of(Named.of("glosa con carácter nulo", "{\"accountId\": \"%s\", \"amount\": 1000, \"description\": \"a" + NUL + "b\"}")),
                Arguments.of(Named.of("glosa con inversión de texto", "{\"accountId\": \"%s\", \"amount\": 1000, \"description\": \"" + RTL_OVERRIDE + "abc\"}")),
                Arguments.of(Named.of("glosa de 10.000 caracteres", "{\"accountId\": \"%s\", \"amount\": 1000, \"description\": \"" + LONG + "\"}")),
                Arguments.of(Named.of("objeto anidado enorme", "{\"accountId\": \"%s\", \"amount\": 1000, \"x\": " + "[".repeat(2_000) + "]".repeat(2_000) + "}")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedBodies")
    void malformedDepositBodyIsRejected(String template) {
        MvcTestResult result = mvc.post().uri("/api/deposits")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(template.formatted(account))
                .exchange();

        assertControlledError(result, 400);
    }

    @Test
    void wrongFieldTypeNamesTheField() {
        MvcTestResult result = mvc.post().uri("/api/deposits")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\": \"%s\", \"amount\": \"mil\"}".formatted(account))
                .exchange();

        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MALFORMED_BODY");
        assertThat(result).bodyJson().extractingPath("$.detail").asString()
                .contains("'amount'").contains("número entero");
    }

    @Test
    void invalidUtf8IsRejected() {
        byte[] invalidUtf8 = {'{', '"', 'a', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};

        MvcTestResult result = mvc.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(invalidUtf8).exchange();

        assertControlledError(result, 400);
    }

    @Test
    void validDescriptionsWithAccentsAndEmojisAreAccepted() {
        MvcTestResult result = mvc.post().uri("/api/deposits")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\": \"%s\", \"amount\": 1000, \"description\": \"Cumpleaños de Ñuñoa 🎂\"}"
                        .formatted(account).getBytes(StandardCharsets.UTF_8))
                .exchange();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(result).bodyJson().extractingPath("$.description").isEqualTo("Cumpleaños de Ñuñoa 🎂");
    }

    // --- transferencias -----------------------------------------------------------------

    Stream<Arguments> invalidDestinations() {
        return Stream.of(
                Arguments.of(Named.of("con espacios", "12345 67897")),
                Arguments.of(Named.of("con guiones", "123-456-789")),
                Arguments.of(Named.of("dígitos arábigos", "١٢٣٤٥٦٧٨٩٧")),
                Arguments.of(Named.of("inyección SQL", "1' OR '1'='1")),
                Arguments.of(Named.of("vacío", "")),
                Arguments.of(Named.of("11 dígitos", "12345678901")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDestinations")
    void invalidDestinationAccountNumberIsRejected(String number) {
        MvcTestResult result = api.transfer(token, account, number, 1_000);

        assertControlledError(result, 400);
    }

    // --- headers, rutas y parámetros ------------------------------------------------------

    Stream<Arguments> invalidIdempotencyKeys() {
        return Stream.of(
                Arguments.of(Named.of("vacía", "")),
                Arguments.of(Named.of("solo espacios", "   ")),
                Arguments.of(Named.of("con espacios", "mi clave")),
                Arguments.of(Named.of("con tildes", "clavé")),
                Arguments.of(Named.of("de 101 caracteres", "k".repeat(101))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidIdempotencyKeys")
    void invalidIdempotencyKeyIsRejected(String key) {
        MvcTestResult result = api.movement("/api/deposits", token, account, 1_000, key);

        assertControlledError(result, 400);
    }

    Stream<Arguments> invalidQueries() {
        return Stream.of(
                Arguments.of(Named.of("id que no es UUID", "/api/accounts/abc")),
                Arguments.of(Named.of("id con inyección", "/api/accounts/1%%20OR%%201=1")),
                Arguments.of(Named.of("fecha inválida", "/api/accounts/%s/transactions?from=ayer")),
                Arguments.of(Named.of("fecha con inyección", "/api/accounts/%s/transactions?from=2026'%%3B--")),
                Arguments.of(Named.of("página demasiado lejana", "/api/accounts/%s/transactions?page=2147483647")),
                Arguments.of(Named.of("página lejana con tamaño máximo", "/api/accounts/%s/transactions?page=10001&size=100")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidQueries")
    void invalidPathOrQueryIsRejected(String uri) {
        MvcTestResult result = api.get(uri.formatted(account), token);

        assertControlledError(result, 400);
    }

    /**
     * Valores de paginación fuera de lo normal que Spring normaliza en vez de rechazar: una página
     * negativa o no numérica se trata como la primera, un tamaño inválido usa el valor por defecto
     * y uno excesivo se limita a 100. Ninguno provoca un error.
     */
    @Test
    void unusualPaginationValuesAreNormalized() {
        for (String query : new String[]{"?page=-1", "?page=abc", "?size=0", "?size=-5", "?size=muchos",
                "?size=2147483647", "?page=9999&size=100"}) {
            MvcTestResult result = api.get("/api/accounts/" + account + "/transactions" + query, token);
            assertThat(result.getResponse().getStatus()).as(query).isEqualTo(200);
            assertThat(result).bodyJson().extractingPath("$.size").as(query).asNumber().matches(size -> size.intValue() <= 100);
        }
    }

    Stream<Arguments> suspiciousPaths() {
        return Stream.of(
                Arguments.of(Named.of("punto y coma para evadir rutas", "/api/accounts/..;/admin/reconciliation")),
                Arguments.of(Named.of("puntos codificados", "/api/%2e%2e/admin/reconciliation")),
                Arguments.of(Named.of("porcentaje doblemente codificado", "/api/accounts/%2525")),
                Arguments.of(Named.of("barra invertida codificada", "/api/accounts/%5C..%5Cadmin")));
    }

    /** El firewall de Spring Security las rechaza antes de llegar a la aplicación, con su motivo. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("suspiciousPaths")
    void suspiciousPathsAreRejectedWithReason(String path) {
        MvcTestResult result = mvc.get().uri(URI.create(path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();

        assertControlledError(result, 400);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("REQUEST_REJECTED");
    }

    // --- protocolo HTTP -------------------------------------------------------------------

    @Test
    void wrongHttpMethodExplainsAllowedMethods() {
        MvcTestResult result = mvc.put().uri("/api/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();

        assertControlledError(result, 405);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("PUT").contains("POST");
    }

    @Test
    void wrongContentTypeIsExplained() {
        MvcTestResult result = mvc.post().uri("/api/deposits")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.TEXT_PLAIN).content("amount=1000").exchange();

        assertControlledError(result, 415);
    }

    @Test
    void unknownRouteReturnsNotFound() {
        assertControlledError(api.get("/api/no-existe", token), 404);
    }

    @Test
    void missingHeaderNamesTheHeader() {
        MvcTestResult result = mvc.post().uri("/api/deposits")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\": \"%s\", \"amount\": 1000}".formatted(account)).exchange();

        assertControlledError(result, 400);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MISSING_HEADER");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("Idempotency-Key");
    }

    // --- helpers --------------------------------------------------------------------------

    private static Arguments registration(String name, String override) {
        String field = override.substring(1, override.indexOf('"', 1));
        String base = """
                {"email": "%s", "password": "secreta123", "firstName": "Ana", "lastName": "Pérez",
                 "rut": "%s", "phone": "+56911112222"}""".formatted(TestData.uniqueEmail(), TestData.randomRut());
        String json = base.replaceFirst("\"" + field + "\": (\"[^\"]*\"|[^,}]+)", java.util.regex.Matcher.quoteReplacement(override));
        return Arguments.of(Named.of(name, json));
    }

    private MvcTestResult post(String uri, String json, String bearer) {
        var request = mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .content(json.getBytes(StandardCharsets.UTF_8));
        if (bearer != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        return request.exchange();
    }

    /**
     * Un error controlado: el código esperado (nunca 500), formato ProblemDetail con code, title y
     * detail, y sin rastros de detalles internos en el cuerpo.
     */
    private static void assertControlledError(MvcTestResult result, int expectedStatus) {
        int status = result.getResponse().getStatus();
        String body;
        try {
            body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(status).as("respuesta: %s", body).isEqualTo(expectedStatus);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String code = JsonPath.read(body, "$.code");
        String detail = JsonPath.read(body, "$.detail");
        assertThat(code).matches("[A-Z_]+");
        assertThat(detail).isNotBlank();
        assertThat(body).doesNotContain("Exception", "java.", "org.springframework", "tools.jackson", "SQL", "\\u0000");
    }
}
