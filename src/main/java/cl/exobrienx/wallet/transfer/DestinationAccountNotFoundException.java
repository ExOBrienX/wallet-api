package cl.exobrienx.wallet.transfer;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class DestinationAccountNotFoundException extends DomainException {

    public DestinationAccountNotFoundException() {
        super(HttpStatus.NOT_FOUND, "Cuenta de destino no encontrada",
                "No existe una cuenta con ese número.");
    }
}
