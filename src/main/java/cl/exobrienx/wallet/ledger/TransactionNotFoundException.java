package cl.exobrienx.wallet.ledger;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class TransactionNotFoundException extends DomainException {

    public TransactionNotFoundException() {
        super(HttpStatus.NOT_FOUND, "Transacción no encontrada", "No existe una transacción con ese identificador.");
    }
}
