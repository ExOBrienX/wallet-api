package cl.exobrienx.wallet.admin;

import cl.exobrienx.wallet.account.dto.AccountResponse;
import cl.exobrienx.wallet.admin.dto.ReversalResponse;
import cl.exobrienx.wallet.admin.dto.ReverseTransactionRequest;
import cl.exobrienx.wallet.admin.dto.UpdateAccountStatusRequest;
import cl.exobrienx.wallet.common.config.ApiError;
import cl.exobrienx.wallet.common.persistence.TransactionRetry;
import cl.exobrienx.wallet.common.security.CurrentUser;
import cl.exobrienx.wallet.ledger.ReconciliationReport;
import cl.exobrienx.wallet.ledger.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Operaciones de administración. Todas las rutas exigen el rol ADMIN (ver {@code SecurityConfig}). */
@Tag(name = "Administración", description = "Requiere rol ADMIN")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminAccountService adminAccountService;
    private final ReversalService reversalService;
    private final ReconciliationService reconciliationService;
    private final TransactionRetry transactionRetry;

    public AdminController(AdminAccountService adminAccountService, ReversalService reversalService,
                           ReconciliationService reconciliationService, TransactionRetry transactionRetry) {
        this.adminAccountService = adminAccountService;
        this.reversalService = reversalService;
        this.reconciliationService = reconciliationService;
        this.transactionRetry = transactionRetry;
    }

    @Operation(summary = "Bloquear o reactivar una cuenta")
    @ApiError(status = 403, description = "Requiere rol ADMIN")
    @ApiError(status = 404, description = "La cuenta no existe")
    @ApiError(status = 409, description = "Otro administrador modificó la cuenta al mismo tiempo")
    @ApiError(status = 422, description = "La cuenta interna del sistema no se puede bloquear")
    @PatchMapping("/accounts/{id}/status")
    public AccountResponse changeAccountStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                               @Valid @RequestBody UpdateAccountStatusRequest request) {
        return adminAccountService.changeStatus(id, request.status(), CurrentUser.from(jwt).id());
    }

    @Operation(summary = "Revertir una transacción", description = "Crea una transacción con los asientos inversos. "
            + "Puede dejar una cuenta en negativo (contracargo).")
    @ApiError(status = 403, description = "Requiere rol ADMIN")
    @ApiError(status = 404, description = "La transacción no existe")
    @ApiError(status = 422, description = "La transacción ya fue revertida o es una reversa")
    @PostMapping("/transactions/{id}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    public ReversalResponse reverse(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                    @Valid @RequestBody(required = false) ReverseTransactionRequest request) {
        String reason = request == null ? null : request.reason();
        UUID adminId = CurrentUser.from(jwt).id();
        return transactionRetry.run(() -> reversalService.reverse(id, adminId, reason));
    }

    @Operation(summary = "Conciliar el libro contable", description = "Verifica que débitos y créditos cuadren e informa "
            + "cuentas de clientes con saldo negativo.")
    @ApiError(status = 403, description = "Requiere rol ADMIN")
    @GetMapping("/reconciliation")
    public ReconciliationReport reconciliation() {
        return reconciliationService.reconcile();
    }
}
