package cl.exobrienx.wallet.common.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara, para la documentación OpenAPI, una respuesta de error que puede devolver un endpoint.
 * {@link OpenApiConfig} la agrega con el esquema ProblemDetail. Los errores comunes a todos los
 * endpoints (400 de validación, 401, 500) se agregan automáticamente y no hace falta declararlos.
 *
 * <pre>{@code @ApiError(status = 422, description = "Saldo insuficiente")}</pre>
 */
@Documented
@Repeatable(ApiError.List.class)
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiError {

    int status();

    String description();

    @Documented
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        ApiError[] value();
    }
}
