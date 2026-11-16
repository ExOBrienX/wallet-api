package cl.exobrienx.wallet.admin;

import cl.exobrienx.wallet.account.Account;
import cl.exobrienx.wallet.account.AccountNotFoundException;
import cl.exobrienx.wallet.account.AccountRepository;
import cl.exobrienx.wallet.account.AccountStatus;
import cl.exobrienx.wallet.account.AccountType;
import cl.exobrienx.wallet.account.dto.AccountResponse;
import cl.exobrienx.wallet.ledger.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AdminAccountService {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountService.class);

    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;

    public AdminAccountService(AccountRepository accountRepository, LedgerService ledgerService) {
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
    }

    /**
     * Bloquea o reactiva una cuenta.
     * <p>
     * Si en ese momento hay una transferencia en curso sobre la cuenta, el UPDATE espera a que
     * termine (la transferencia tiene la fila bloqueada con FOR UPDATE). El {@code @Version} de la
     * cuenta detecta además si dos administradores la modifican a la vez: el segundo recibe 409
     * en vez de pisar el cambio del primero sin saberlo.
     */
    @Transactional
    public AccountResponse changeStatus(UUID accountId, AccountStatus status, UUID adminId) {
        Account account = accountRepository.findById(accountId).orElseThrow(AccountNotFoundException::new);
        if (account.getType() == AccountType.SYSTEM) {
            throw new SystemAccountModificationException();
        }
        if (account.getStatus() != status) {
            account.changeStatus(status);
            accountRepository.saveAndFlush(account);
            log.info("Cuenta {} cambiada a {} por el administrador {}", account.getAccountNumber(), status, adminId);
        }
        return AccountResponse.from(account, ledgerService.balanceOf(accountId));
    }
}
