package cl.exobrienx.wallet.transfer;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Formato del header Idempotency-Key: entre 1 y 100 caracteres ASCII visibles, sin espacios
 * (letras, dígitos y símbolos). Un UUID cumple el formato.
 */
@Documented
@NotBlank
@Pattern(regexp = "^[\\x21-\\x7E]{1,100}$")
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotencyKeyFormat {

    String message() default "debe tener entre 1 y 100 caracteres visibles, sin espacios (se recomienda un UUID)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
