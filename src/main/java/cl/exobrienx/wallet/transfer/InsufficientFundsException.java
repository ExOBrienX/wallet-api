package cl.exobrienx.wallet.transfer;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class InsufficientFundsException extends DomainException {

    public InsufficientFundsException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "Saldo insuficiente",
                "La cuenta no tiene saldo suficiente para esta operación.");
    }
}
