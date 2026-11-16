package cl.exobrienx.wallet.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @Schema(example = "camila.rojas@demo.cl") @NotBlank @Size(max = 254) String email,
        @Schema(example = "demo12345") @NotBlank @Size(max = 72) String password) {
}
