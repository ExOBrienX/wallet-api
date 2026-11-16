package cl.exobrienx.wallet.admin.dto;

import cl.exobrienx.wallet.account.AccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record UpdateAccountStatusRequest(
        @Schema(example = "BLOCKED", description = "BLOCKED para bloquear, ACTIVE para reactivar")
        @NotNull AccountStatus status) {
}
