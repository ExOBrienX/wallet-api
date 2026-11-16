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
 * Texto libre seguro de guardar y de mostrar: rechaza caracteres de control (incluido el
 * carácter nulo, que PostgreSQL no acepta en columnas de texto) y los caracteres invisibles que
 * invierten la dirección del texto, usados para disfrazar contenido. Se permiten letras con
 * tilde, ñ, signos de puntuación y emojis. Los valores nulos se consideran válidos.
 */
@Documented
@Constraint(validatedBy = SafeText.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeText {

    String message() default "contiene caracteres no permitidos";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<SafeText, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.codePoints().noneMatch(Validator::isForbidden);
        }

        static boolean isForbidden(int codePoint) {
            return Character.getType(codePoint) == Character.CONTROL
                    || (codePoint >= 0x202A && codePoint <= 0x202E)   // incrustaciones y anulaciones de dirección
                    || (codePoint >= 0x2066 && codePoint <= 0x2069)   // aislamientos de dirección
                    || codePoint == 0xFEFF;                            // marca de orden de bytes
        }
    }
}
