package cl.exobrienx.wallet.transfer;

import cl.exobrienx.wallet.common.config.ApiError;
import cl.exobrienx.wallet.common.persistence.TransactionRetry;
import cl.exobrienx.wallet.common.security.CurrentUser;
import cl.exobrienx.wallet.transfer.dto.CashMovementRequest;
import cl.exobrienx.wallet.transfer.dto.MovementResponse;
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

/**
 * Depósitos y retiros. Ambos exigen el header {@code Idempotency-Key}: si el cliente reintenta
 * una petición (por ejemplo, tras un timeout), recibe la respuesta original y la operación no
 * se ejecuta dos veces.
 */
@Tag(name = "Depósitos y retiros", description = "Dinero que entra o sale de la billetera (simulado)")
@RestController
public class CashMovementController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CashMovementService cashMovementService;
    private final IdempotencyService idempotencyService;
    private final TransactionRetry transactionRetry;

    public CashMovementController(CashMovementService cashMovementService, IdempotencyService idempotencyService,
                                  TransactionRetry transactionRetry) {
        this.cashMovementService = cashMovementService;
        this.idempotencyService = idempotencyService;
        this.transactionRetry = transactionRetry;
    }

    @Operation(summary = "Depositar", description = "Requiere Idempotency-Key. Repetir la misma petición devuelve la respuesta original.")
    @ApiResponse(responseCode = "201", description = "Depósito registrado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = MovementResponse.class)))
    @ApiError(status = 404, description = "La cuenta no existe o no pertenece al usuario")
    @ApiError(status = 422, description = "Cuenta bloqueada, o Idempotency-Key ya usada con otra petición")
    @ApiError(status = 503, description = "Conflicto transitorio de concurrencia; reintentar con la misma Idempotency-Key")
    @PostMapping("/api/deposits")
    public ResponseEntity<String> deposit(@AuthenticationPrincipal Jwt jwt,
                                          @IdempotencyKeyParameter @RequestHeader(IDEMPOTENCY_KEY) @IdempotencyKeyFormat
                                          String idempotencyKey,
                                          @Valid @RequestBody CashMovementRequest request) {
        UUID userId = CurrentUser.from(jwt).id();
        // Reintento ante conflictos transitorios; cada intento es una transacción nueva.
        IdempotentResponse response = transactionRetry.run(() -> idempotencyService.execute(
                userId, idempotencyKey, "POST /api/deposits", request, HttpStatus.CREATED,
                () -> cashMovementService.deposit(userId, idempotencyKey, request)));
        return response.toResponseEntity();
    }

    @Operation(summary = "Retirar", description = "Requiere Idempotency-Key. Falla con 422 si el saldo no alcanza.")
    @ApiResponse(responseCode = "201", description = "Retiro registrado",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = MovementResponse.class)))
    @ApiError(status = 404, description = "La cuenta no existe o no pertenece al usuario")
    @ApiError(status = 422, description = "Saldo insuficiente, cuenta bloqueada, o Idempotency-Key ya usada con otra petición")
    @ApiError(status = 503, description = "Conflicto transitorio de concurrencia; reintentar con la misma Idempotency-Key")
    @PostMapping("/api/withdrawals")
    public ResponseEntity<String> withdraw(@AuthenticationPrincipal Jwt jwt,
                                           @IdempotencyKeyParameter @RequestHeader(IDEMPOTENCY_KEY) @IdempotencyKeyFormat
                                           String idempotencyKey,
                                           @Valid @RequestBody CashMovementRequest request) {
        UUID userId = CurrentUser.from(jwt).id();
        // Reintento ante conflictos transitorios; cada intento es una transacción nueva.
        IdempotentResponse response = transactionRetry.run(() -> idempotencyService.execute(
                userId, idempotencyKey, "POST /api/withdrawals", request, HttpStatus.CREATED,
                () -> cashMovementService.withdraw(userId, idempotencyKey, request)));
        return response.toResponseEntity();
    }
}
