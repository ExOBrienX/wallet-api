package cl.exobrienx.wallet.account;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class InvalidDateRangeException extends DomainException {

    public InvalidDateRangeException() {
        super(HttpStatus.BAD_REQUEST, "Rango de fechas inválido", "La fecha 'from' debe ser anterior a 'to'.");
    }
}
