package cl.exobrienx.wallet.common;

import cl.exobrienx.wallet.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/** CORS con un frontend autorizado: solo ese origen puede llamar a la API desde un navegador. */
@SpringBootTest(properties = "wallet.security.cors.allowed-origins=https://app.wallet.cl")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CorsConfiguredIT {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void configuredOriginIsAllowedWithIdempotencyHeader() {
        MvcTestResult preflight = preflight("https://app.wallet.cl");

        assertThat(preflight).hasStatusOk();
        assertThat(preflight).hasHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.wallet.cl");
        assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
                .containsIgnoringCase("Idempotency-Key").containsIgnoringCase("Authorization");
    }

    @Test
    void otherOriginsAreStillRejected() {
        MvcTestResult preflight = preflight("https://sitio-malicioso.com");

        assertThat(preflight.getResponse().getStatus()).isEqualTo(403);
        assertThat(preflight).doesNotContainHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    private MvcTestResult preflight(String origin) {
        return mvc.options().uri("/api/transfers")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization, content-type, idempotency-key")
                .exchange();
    }
}
