package cl.exobrienx.wallet.auth.dto;

import cl.exobrienx.wallet.common.validation.ValidRut;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

/**
 * Registro de un cliente.
 *
 * @param rut   se acepta con o sin puntos ("12.345.678-5" o "12345678-5")
 * @param phone opcional; celular chileno en formato +569XXXXXXXX
 */
public record RegisterRequest(
        @Schema(example = "ana.perez@correo.cl")
        @NotBlank @Email @Size(max = 254) String email,

        @Schema(example = "MiClave2026", description = "Entre 8 y 72 caracteres")
        @NotBlank @Size(min = 8, max = 72) String password,

        @Schema(example = "Ana")
        @NotBlank @Size(max = 60) @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE) String firstName,

        @Schema(example = "Pérez Soto")
        @NotBlank @Size(max = 60) @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE) String lastName,

        @Schema(example = "12.345.678-5", description = "Con o sin puntos; se valida el dígito verificador")
        @NotBlank @ValidRut String rut,

        @Schema(example = "+56912345678", description = "Opcional")
        @Pattern(regexp = "^\\+569\\d{8}$", message = "debe tener el formato +569XXXXXXXX") String phone) {

    /**
     * Palabras de letras (con tildes, ñ y diéresis) separadas por espacios, apóstrofos, puntos o
     * guiones: acepta "María-José", "O'Higgins" o "Pérez Soto"; rechaza números, símbolos y
     * caracteres de control.
     */
    static final String NAME_PATTERN = "^[\\p{L}\\p{M}]+(?:[ '.\\-]+[\\p{L}\\p{M}]+)*\\.?$";

    static final String NAME_MESSAGE = "solo puede contener letras, espacios, apóstrofos, puntos y guiones";

    /**
     * BCrypt solo considera los primeros 72 bytes de la contraseña. Con caracteres no ASCII
     * (tildes, ñ) 72 caracteres pueden superar ese límite, así que se valida en bytes.
     */
    @AssertTrue(message = "la contraseña no puede superar 72 bytes")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
