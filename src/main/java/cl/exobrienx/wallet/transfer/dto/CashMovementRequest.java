package cl.exobrienx.wallet.transfer.dto;

import cl.exobrienx.wallet.common.validation.SafeText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Depósito o retiro sobre una cuenta propia.
 *
 * @param amount      pesos enteros; el tope por operación evita montos absurdos y desbordes
 * @param description glosa opcional ("Sueldo noviembre")
 */
public record CashMovementRequest(
        @Schema(description = "Cuenta propia sobre la que se opera") @NotNull UUID accountId,
        @Schema(example = "150000", description = "Pesos enteros, entre 1 y 100.000.000")
        @NotNull @Positive @Max(MAX_AMOUNT) Long amount,
        @Schema(example = "Sueldo noviembre", description = "Glosa opcional") @SafeText @Size(max = 140) String description) {

    /** Monto máximo por operación: 100 millones de pesos. */
    public static final long MAX_AMOUNT = 100_000_000L;
}
