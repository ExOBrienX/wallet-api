package cl.exobrienx.wallet.auth;

import cl.exobrienx.wallet.auth.dto.LoginRequest;
import cl.exobrienx.wallet.auth.dto.RefreshTokenRequest;
import cl.exobrienx.wallet.auth.dto.RegisterRequest;
import cl.exobrienx.wallet.auth.dto.TokenResponse;
import cl.exobrienx.wallet.common.config.ApiError;
import cl.exobrienx.wallet.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Autenticación", description = "Registro, inicio de sesión y renovación de tokens")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Registrar un cliente", description = "Crea un usuario con rol USER. El RUT se acepta con o sin puntos.")
    @ApiError(status = 409, description = "Ya existe un usuario con ese correo o con ese RUT")
    @SecurityRequirements
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @Operation(summary = "Iniciar sesión", description = "Devuelve un token de acceso (15 minutos) y un refresh token (7 días).")
    @ApiError(status = 401, description = "Correo o contraseña incorrectos (mismo mensaje en ambos casos)")
    @ApiError(status = 429, description = "Demasiados intentos fallidos; el header Retry-After indica cuándo reintentar")
    @SecurityRequirements
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request, httpRequest.getRemoteAddr());
    }

    @Operation(summary = "Renovar tokens", description = "Entrega tokens nuevos y revoca el refresh token usado. "
            + "Reutilizar un refresh token ya revocado cierra todas las sesiones del usuario.")
    @ApiError(status = 401, description = "Refresh token inexistente, expirado o revocado")
    @SecurityRequirements
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @Operation(summary = "Cerrar sesión", description = "Revoca el refresh token indicado.")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(UUID.fromString(jwt.getSubject()), request.refreshToken());
    }
}
