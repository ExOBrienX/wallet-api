package cl.exobrienx.wallet.account;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class AccountBlockedException extends DomainException {

    public AccountBlockedException(String accountNumber) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "Cuenta bloqueada",
                "La cuenta " + accountNumber + " está bloqueada y no puede operar.");
    }
}
