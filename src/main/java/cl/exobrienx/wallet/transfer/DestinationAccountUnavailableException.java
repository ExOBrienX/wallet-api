package cl.exobrienx.wallet.transfer;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** La cuenta de destino existe pero no puede recibir dinero (está bloqueada). */
public class DestinationAccountUnavailableException extends DomainException {

    public DestinationAccountUnavailableException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "Cuenta de destino no disponible",
                "La cuenta de destino no puede recibir transferencias en este momento.");
    }
}
