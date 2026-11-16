package cl.exobrienx.wallet.account;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class PageOutOfRangeException extends DomainException {

    public PageOutOfRangeException(long maxOffset) {
        super(HttpStatus.BAD_REQUEST, "Página fuera de rango",
                "La página solicitada está fuera del rango permitido: se pueden recorrer hasta "
                        + maxOffset + " movimientos. Usa los filtros de fecha para acotar la búsqueda.");
    }
}
