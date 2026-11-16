package cl.exobrienx.wallet.transfer;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class SameAccountTransferException extends DomainException {

    public SameAccountTransferException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "Transferencia inválida",
                "La cuenta de origen y la de destino no pueden ser la misma.");
    }
}
