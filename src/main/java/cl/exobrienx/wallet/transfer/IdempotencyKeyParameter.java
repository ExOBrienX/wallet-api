package cl.exobrienx.wallet.transfer;

import io.swagger.v3.oas.annotations.Parameter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Documentación OpenAPI del header Idempotency-Key, compartida por los endpoints que mueven dinero. */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Parameter(
        description = """
                Identificador único de la operación, generado por el cliente (se recomienda un UUID). \
                Si la petición se repite con la misma clave y el mismo cuerpo, se devuelve la respuesta \
                original sin volver a ejecutarla; con un cuerpo distinto se responde 422. Una operación \
                que falla no consume la clave.""",
        example = "6f1c2e2a-8a4b-4f3e-9c1d-2b7e5a9d0c11")
public @interface IdempotencyKeyParameter {
}
