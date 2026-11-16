package cl.exobrienx.wallet.transfer.dto;

import cl.exobrienx.wallet.common.validation.SafeText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Transferencia desde una cuenta propia hacia cualquier cuenta, identificada por su número.
 *
 * @param destinationAccountNumber número de 10 dígitos de la cuenta que recibe
 * @param description              glosa opcional, visible también para quien recibe
 */
public record TransferRequest(
        @Schema(description = "Cuenta propia desde la que se transfiere") @NotNull UUID sourceAccountId,
        @Schema(example = "1234567897", description = "10 dígitos; el último es verificador (Luhn)")
        @NotBlank @Pattern(regexp = "\\d{10}", message = "debe tener 10 dígitos") String destinationAccountNumber,
        @Schema(example = "45000", description = "Pesos enteros; sujeto al límite diario de la cuenta de origen")
        @NotNull @Positive @Max(CashMovementRequest.MAX_AMOUNT) Long amount,
        @Schema(example = "Mitad del asado", description = "Glosa opcional, visible para quien recibe")
        @SafeText @Size(max = 140) String description) {
}
