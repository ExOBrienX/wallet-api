package cl.exobrienx.wallet.user;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class RutAlreadyRegisteredException extends DomainException {

    public RutAlreadyRegisteredException() {
        super(HttpStatus.CONFLICT, "RUT ya registrado", "Ya existe un usuario con ese RUT.");
    }
}
