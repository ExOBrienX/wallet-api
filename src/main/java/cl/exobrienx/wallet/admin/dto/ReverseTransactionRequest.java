package cl.exobrienx.wallet.admin.dto;

import cl.exobrienx.wallet.common.validation.SafeText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * @param reason motivo de la reversa; queda en la glosa
 */
public record ReverseTransactionRequest(
        @Schema(example = "Cargo desconocido por el cliente", description = "Opcional; queda en la glosa")
        @SafeText @Size(max = 100) String reason) {
}
