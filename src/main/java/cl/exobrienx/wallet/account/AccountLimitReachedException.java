package cl.exobrienx.wallet.account;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class AccountLimitReachedException extends DomainException {

    public AccountLimitReachedException(int max) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "Límite de cuentas alcanzado",
                "No puedes tener más de " + max + " cuentas.");
    }
}
