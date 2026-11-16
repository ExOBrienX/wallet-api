package cl.exobrienx.wallet.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuración de los tokens, bajo el prefijo {@code wallet.security.jwt}.
 *
 * @param secret          clave HMAC para firmar los JWT; debe tener al menos 32 caracteres (256 bits)
 * @param issuer          valor del claim {@code iss}; los tokens con otro emisor se rechazan
 * @param accessTokenTtl  duración del token de acceso
 * @param refreshTokenTtl duración del refresh token
 */
@Validated
@ConfigurationProperties(prefix = "wallet.security.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "debe tener al menos 32 caracteres") String secret,
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl) {
}
