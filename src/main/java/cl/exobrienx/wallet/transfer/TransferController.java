package cl.exobrienx.wallet.transfer;

import cl.exobrienx.wallet.common.config.ApiError;
import cl.exobrienx.wallet.common.persistence.TransactionRetry;
import cl.exobrienx.wallet.common.security.CurrentUser;
import cl.exobrienx.wallet.transfer.dto.TransferRequest;
import cl.exobrienx.wallet.transfer.dto.TransferResponse;
import cl.exobrienx.wallet.transfer.idempotency.IdempotencyService;
import cl.exobrienx.wallet.transfer.idempotency.IdempotentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static cl.exobrienx.wallet.transfer.CashMovementController.IDEMPOTENCY_KEY;

@Tag(name = "Transferencias")
@RestController
public class TransferController {

    private final TransferService transferService;
    private final IdempotencyService idempotencyService;
    private final TransactionRetry transactionRetry;

    public TransferController(TransferService transferService, IdempotencyService idempotencyService,
                              TransactionRetry transactionRetry) {
        this.transferService = transferService;
        this.idempotencyService = idempotencyService;
        this.transactionRetry = transactionRetry;
    }

    @Operation(summary = "Transferir", description = "Desde una cuenta propia hacia un número de cuenta. Requiere "
            + "Idempotency-Key. Valida saldo, estado de ambas cuentas y límite diario (hora de Chile).")
    @ApiResponse(responseCode = "201", description = "Transferencia realizada",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = TransferResponse.class)))
    @ApiError(status = 404, description = "La cuenta de origen no pertenece al usuario, o no existe una cuenta con el número de destino")
    @ApiError(status = 422, description = "Saldo insuficiente, límite diario excedido, misma cuenta, cuenta bloqueada, "
            + "o Idempotency-Key ya usada con otra petición")
    @ApiError(status = 503, description = "Conflicto transitorio de concurrencia; reintentar con la misma Idempotency-Key")
    @PostMapping("/api/transfers")
    public ResponseEntity<String> transfer(@AuthenticationPrincipal Jwt jwt,
                                           @IdempotencyKeyParameter @RequestHeader(IDEMPOTENCY_KEY) @IdempotencyKeyFormat
                                           String idempotencyKey,
                                           @Valid @RequestBody TransferRequest request) {
        UUID userId = CurrentUser.from(jwt).id();
        // Reintento ante conflictos transitorios; cada intento es una transacción nueva.
        IdempotentResponse response = transactionRetry.run(() -> idempotencyService.execute(
                userId, idempotencyKey, "POST /api/transfers", request, HttpStatus.CREATED,
                () -> transferService.transfer(userId, idempotencyKey, request)));
        return response.toResponseEntity();
    }
}
