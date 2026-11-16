package cl.exobrienx.wallet.account.dto;

import cl.exobrienx.wallet.common.validation.SafeText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * @param alias nombre opcional para identificar la cuenta ("Ahorro", "Gastos")
 */
public record OpenAccountRequest(
        @Schema(example = "Ahorro vacaciones", description = "Opcional") @SafeText @Size(max = 40) String alias) {
}
