package cl.exobrienx.wallet.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cuerpo de {@code /api/auth/refresh} y {@code /api/auth/logout}. */
public record RefreshTokenRequest(
        @Schema(description = "Refresh token recibido en el login o en el último refresh",
                example = "Jx3m0Q8pV2rT6yN1kL9wZ4bH7cF5dA0sE2gU8iO3qR1")
        @NotBlank @Size(max = 100) String refreshToken) {
}
