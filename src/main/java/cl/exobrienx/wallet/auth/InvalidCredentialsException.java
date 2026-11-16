package cl.exobrienx.wallet.auth;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Login fallido. El mensaje es el mismo si el correo no existe o si la contraseña es
 * incorrecta, para no revelar qué correos están registrados.
 */
public class InvalidCredentialsException extends DomainException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "Credenciales inválidas", "Correo o contraseña incorrectos.");
    }
}
