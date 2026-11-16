package cl.exobrienx.wallet.account;

import cl.exobrienx.wallet.account.dto.AccountResponse;
import cl.exobrienx.wallet.account.dto.OpenAccountRequest;
import cl.exobrienx.wallet.common.config.ApiError;
import cl.exobrienx.wallet.common.security.CurrentUser;
import cl.exobrienx.wallet.common.web.PageResponse;
import cl.exobrienx.wallet.ledger.AccountMovement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Tag(name = "Cuentas", description = "Apertura, consulta de saldo e historial")
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** Abre una cuenta. El cuerpo es opcional: sin él, la cuenta se crea sin alias. */
    @Operation(summary = "Abrir una cuenta", description = "Cuenta en CLP con número de 10 dígitos. Máximo 5 por cliente.")
    @ApiError(status = 403, description = "Solo los clientes pueden abrir cuentas")
    @ApiError(status = 422, description = "El cliente ya tiene el máximo de cuentas permitido")
    @PostMapping
    public ResponseEntity<AccountResponse> open(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody(required = false) OpenAccountRequest request) {
        String alias = request == null ? null : request.alias();
        AccountResponse account = accountService.open(CurrentUser.from(jwt).id(), alias);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(account.id()).toUri();
        return ResponseEntity.created(location).body(account);
    }

    @Operation(summary = "Listar mis cuentas", description = "Incluye el saldo de cada cuenta, calculado desde el libro contable.")
    @GetMapping
    public List<AccountResponse> listOwn(@AuthenticationPrincipal Jwt jwt) {
        return accountService.listOwn(CurrentUser.from(jwt).id());
    }

    @Operation(summary = "Detalle de una cuenta", description = "Un cliente solo ve sus cuentas; un administrador, cualquiera.")
    @ApiError(status = 404, description = "La cuenta no existe o no pertenece al usuario")
    @GetMapping("/{id}")
    public AccountResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return accountService.get(id, CurrentUser.from(jwt));
    }

    /**
     * Historial paginado. Fechas en ISO-8601 UTC (ej. 2026-11-01T00:00:00Z); tamaño de página
     * por defecto 20 y máximo 100.
     */
    @Operation(summary = "Historial de movimientos", description = "Del más reciente al más antiguo. Montos con signo: "
            + "positivo si entró dinero, negativo si salió.")
    @ApiError(status = 404, description = "La cuenta no existe o no pertenece al usuario")
    @GetMapping("/{id}/transactions")
    public PageResponse<AccountMovement> movements(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Parameter(description = "Desde esta fecha, incluida (ISO-8601 UTC)", example = "2026-11-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Hasta esta fecha, excluida (ISO-8601 UTC)", example = "2026-12-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return accountService.movements(id, CurrentUser.from(jwt), from, to, pageable);
    }
}
