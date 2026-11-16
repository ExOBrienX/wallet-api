package cl.exobrienx.wallet.transfer;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** El número de cuenta no pasa la validación del dígito verificador: casi seguro un error de tipeo. */
public class InvalidAccountNumberException extends DomainException {

    public InvalidAccountNumberException() {
        super(HttpStatus.BAD_REQUEST, "Número de cuenta inválido",
                "El número de cuenta de destino no es válido. Revisa que esté bien escrito.");
    }
}
