package cl.exobrienx.wallet.common;

import cl.exobrienx.wallet.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Peticiones que rechaza Tomcat antes de llegar a Spring. Se prueba con el servidor real (no con
 * MockMvc, que no pasa por Tomcat): la respuesta debe ser ProblemDetail y no la página HTML.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TomcatErrorIT {

    @LocalServerPort
    private int port;

    @Test
    void urlRejectedByTomcatAnswersWithProblemDetail() throws Exception {
        HttpResponse<String> response = get("/api/accounts/%5C..%5Cadmin");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body()).contains("\"code\":\"REQUEST_REJECTED\"").doesNotContain("<html", "Tomcat");
    }

    @Test
    void errorsHandledBySpringKeepTheirOwnBody() throws Exception {
        HttpResponse<String> response = get("/api/accounts");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"AUTHENTICATION_REQUIRED\"");
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
