package cl.exobrienx.wallet.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Valida que el campo sea un RUT chileno con dígito verificador correcto.
 * Los valores nulos se consideran válidos; para exigirlo, combinar con {@code @NotBlank}.
 */
@Documented
@Constraint(validatedBy = ValidRut.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidRut {

    String message() default "no es un RUT válido";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidRut, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || Rut.isValid(value);
        }
    }
}
