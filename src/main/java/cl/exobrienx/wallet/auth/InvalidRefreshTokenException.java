package cl.exobrienx.wallet.auth;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Refresh token inexistente, expirado o revocado. No se distingue el motivo ante el cliente.
 */
public class InvalidRefreshTokenException extends DomainException {

    public InvalidRefreshTokenException() {
        super(HttpStatus.UNAUTHORIZED, "Refresh token inválido",
                "El refresh token no es válido, expiró o fue revocado. Inicia sesión nuevamente.");
    }
}
