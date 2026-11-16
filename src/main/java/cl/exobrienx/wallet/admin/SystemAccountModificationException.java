package cl.exobrienx.wallet.admin;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class SystemAccountModificationException extends DomainException {

    public SystemAccountModificationException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "Cuenta del sistema",
                "La cuenta interna del sistema no se puede bloquear.");
    }
}
