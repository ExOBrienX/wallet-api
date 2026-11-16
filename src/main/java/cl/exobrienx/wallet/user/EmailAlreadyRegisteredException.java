package cl.exobrienx.wallet.user;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class EmailAlreadyRegisteredException extends DomainException {

    public EmailAlreadyRegisteredException() {
        super(HttpStatus.CONFLICT, "Correo ya registrado", "Ya existe un usuario con ese correo.");
    }
}
