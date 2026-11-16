package cl.exobrienx.wallet.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;

import java.util.List;
import java.util.Map;

/**
 * Documentación OpenAPI, visible en {@code /swagger-ui.html}.
 * <p>
 * Todas las operaciones exigen un token Bearer, salvo las marcadas como públicas. En Swagger UI
 * se obtiene el token con {@code POST /api/auth/login} y se ingresa con el botón "Authorize".
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER = "bearer-jwt";

    private static final String PROBLEM_SCHEMA = "Problema";
    private static final String PROBLEM_MEDIA_TYPE = "application/problem+json";

    @Bean
    OpenAPI walletOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Wallet API")
                        .version("1.0")
                        .description("""
                                Billetera digital con libro contable de doble entrada. Los clientes abren \
                                cuentas en pesos chilenos, depositan, retiran y se transfieren dinero simulado.

                                **Cómo probar:** `POST /api/auth/login` con un cliente de demostración \
                                (por ejemplo `camila.rojas@demo.cl` / `demo12345`), copiar el `accessToken` \
                                y pegarlo en el botón *Authorize*.

                                **Idempotencia:** depósitos, retiros y transferencias exigen el header \
                                `Idempotency-Key` con un valor único por operación (se recomienda un UUID). \
                                Repetir la petición con la misma clave devuelve la respuesta original sin \
                                volver a mover dinero.

                                **Errores:** todas las respuestas de error usan el formato ProblemDetail \
                                (RFC 9457)."""))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Token de acceso obtenido en /api/auth/login (dura 15 minutos)."))
                        .addSchemas(PROBLEM_SCHEMA, problemSchema()))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /**
     * Completa las respuestas de error de cada operación: las declaradas con {@link ApiError} y
     * las comunes (400 si recibe datos, 401 si exige token, 500 siempre).
     */
    @Bean
    OperationCustomizer errorResponsesCustomizer() {
        return (operation, handlerMethod) -> {
            ApiResponses responses = operation.getResponses();
            for (ApiError error : handlerMethod.getMethod().getAnnotationsByType(ApiError.class)) {
                responses.addApiResponse(String.valueOf(error.status()), problem(error.description()));
            }
            if (receivesInput(operation, handlerMethod)) {
                responses.putIfAbsent("400", problem("Datos inválidos: el detalle de cada campo viene en `errors`."));
            }
            if (requiresToken(operation)) {
                responses.putIfAbsent("401", problem("Falta el token de acceso, no es válido o expiró."));
            }
            responses.putIfAbsent("500", problem("Error inesperado. No incluye detalles internos."));
            return operation;
        };
    }

    private static boolean receivesInput(Operation operation, HandlerMethod handlerMethod) {
        return operation.getRequestBody() != null
                || (operation.getParameters() != null && !operation.getParameters().isEmpty())
                || handlerMethod.getMethodParameters().length > 1;
    }

    /** Las operaciones públicas declaran una lista de seguridad vacía (@SecurityRequirements). */
    private static boolean requiresToken(Operation operation) {
        return operation.getSecurity() == null || !operation.getSecurity().isEmpty();
    }

    private static ApiResponse problem(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(PROBLEM_MEDIA_TYPE,
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA))));
    }

    @SuppressWarnings("rawtypes")
    private static Schema problemSchema() {
        return new ObjectSchema()
                .description("Error en formato ProblemDetail (RFC 9457).")
                .addProperty("type", new StringSchema().example("about:blank"))
                .addProperty("title", new StringSchema().example("Saldo insuficiente"))
                .addProperty("status", new IntegerSchema().example(422))
                .addProperty("code", new StringSchema().example("INSUFFICIENT_FUNDS")
                        .description("Código estable del error, para que el cliente decida qué hacer sin leer el texto."))
                .addProperty("detail", new StringSchema().example("La cuenta no tiene saldo suficiente para esta operación."))
                .addProperty("instance", new StringSchema().example("/api/transfers"))
                .addProperty("errors", new ObjectSchema()
                        .description("Solo en errores de validación: mensajes por campo.")
                        .additionalProperties(new ArraySchema().items(new StringSchema()))
                        .example(Map.of("amount", List.of("debe ser mayor que 0"))));
    }
}
