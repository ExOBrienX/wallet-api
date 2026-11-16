package cl.exobrienx.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class WalletApplicationIT {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void healthIsPublicAndUp() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("UP");
    }

    @Test
    void infoIsPublic() {
        assertThat(mvc.get().uri("/actuator/info"))
                .hasStatusOk()
                .bodyJson().extractingPath("$.app.name").isEqualTo("Wallet API");
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() {
        // env y beans revelan configuración interna: no deben estar accesibles.
        assertThat(mvc.get().uri("/actuator/env")).hasStatus4xxClientError();
        assertThat(mvc.get().uri("/actuator/beans")).hasStatus4xxClientError();
    }

    @Test
    void openApiDocumentationIsPublicAndListsAllEndpoints() {
        var docs = assertThat(mvc.get().uri("/v3/api-docs")).hasStatusOk().bodyJson();
        docs.extractingPath("$.info.title").isEqualTo("Wallet API");
        for (String path : new String[]{"/api/auth/register", "/api/auth/login", "/api/auth/refresh",
                "/api/auth/logout", "/api/accounts", "/api/accounts/{id}", "/api/accounts/{id}/transactions",
                "/api/deposits", "/api/withdrawals", "/api/transfers", "/api/admin/accounts/{id}/status",
                "/api/admin/transactions/{id}/reverse", "/api/admin/reconciliation"}) {
            docs.hasPath("$.paths['" + path + "']");
        }
        // Las respuestas idempotentes se devuelven como JSON ya serializado; el esquema se declara a mano.
        docs.hasPath("$.components.schemas.TransferResponse");
        assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatusOk();
    }

    @Test
    void openApiDocumentsErrorsHeadersAndExamples() {
        var docs = assertThat(mvc.get().uri("/v3/api-docs")).hasStatusOk().bodyJson();
        String transfer = "$.paths['/api/transfers'].post";

        // Errores de negocio declarados con @ApiError y errores comunes agregados automáticamente.
        for (String status : new String[]{"400", "401", "404", "422", "500", "503"}) {
            docs.hasPath(transfer + ".responses['" + status + "']");
        }
        docs.extractingPath(transfer + ".responses['422'].content['application/problem+json'].schema['$ref']")
                .isEqualTo("#/components/schemas/Problema");

        // El header Idempotency-Key está documentado, con ejemplo.
        docs.extractingPath(transfer + ".parameters[?(@.name == 'Idempotency-Key')].description").asArray()
                .singleElement().asString().contains("Identificador único de la operación");

        // Los endpoints públicos no declaran 401; los protegidos sí.
        docs.doesNotHavePath("$.paths['/api/auth/register'].post.responses['401']");
        docs.hasPath("$.paths['/api/auth/register'].post.responses['409']");

        // Los DTOs tienen ejemplos realistas (RUT y número de cuenta con dígito verificador válido).
        docs.extractingPath("$.components.schemas.RegisterRequest.properties.rut.example").isEqualTo("12.345.678-5");
        docs.extractingPath("$.components.schemas.TransferRequest.properties.destinationAccountNumber.example")
                .isEqualTo("1234567897");
    }

    @Test
    void unknownRoutesRequireAuthentication() {
        assertThat(mvc.get().uri("/api/accounts")).hasStatus4xxClientError();
    }
}
