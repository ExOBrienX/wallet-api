package cl.exobrienx.wallet.account;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * La cuenta no existe o no pertenece al usuario. Ambos casos responden igual (404) para no
 * revelar qué identificadores de cuenta existen.
 */
public class AccountNotFoundException extends DomainException {

    public AccountNotFoundException() {
        super(HttpStatus.NOT_FOUND, "Cuenta no encontrada", "La cuenta no existe o no tienes acceso a ella.");
    }
}
