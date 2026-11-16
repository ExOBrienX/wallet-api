package cl.exobrienx.wallet.transfer;

import cl.exobrienx.wallet.account.Account;
import cl.exobrienx.wallet.account.AccountService;
import cl.exobrienx.wallet.common.util.Text;
import cl.exobrienx.wallet.ledger.LedgerService;
import cl.exobrienx.wallet.ledger.Posting;
import cl.exobrienx.wallet.ledger.Transaction;
import cl.exobrienx.wallet.ledger.TransactionType;
import cl.exobrienx.wallet.transfer.dto.CashMovementRequest;
import cl.exobrienx.wallet.transfer.dto.MovementResponse;
import cl.exobrienx.wallet.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Depósitos y retiros simulados: dinero que entra o sale de la billetera.
 * <p>
 * Para mantener la doble entrada, la contraparte es la cuenta interna del sistema. Un
 * depósito acredita la cuenta del usuario y debita la del sistema; un retiro, al revés.
 */
@Service
public class CashMovementService {

    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final UserRepository userRepository;

    public CashMovementService(AccountService accountService, LedgerService ledgerService,
                               UserRepository userRepository) {
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.userRepository = userRepository;
    }

    @Transactional
    public MovementResponse deposit(UUID userId, String idempotencyKey, CashMovementRequest request) {
        // Se bloquea la cuenta aunque un depósito no valide saldo: así no puede registrarse
        // un depósito mientras un administrador la está bloqueando.
        Account account = accountService.lockOwnActiveAccount(request.accountId(), userId);
        long amount = request.amount();

        Transaction transaction = ledgerService.record(TransactionType.DEPOSIT,
                userRepository.getReferenceById(userId), idempotencyKey, Text.blankToNull(request.description()),
                List.of(Posting.debit(accountService.systemAccount(), amount), Posting.credit(account, amount)));

        return MovementResponse.of(transaction, account, amount, ledgerService.balanceOf(account.getId()));
    }

    @Transactional
    public MovementResponse withdraw(UUID userId, String idempotencyKey, CashMovementRequest request) {
        Account account = accountService.lockOwnActiveAccount(request.accountId(), userId);
        long amount = request.amount();

        // El saldo se lee después del bloqueo: ninguna otra operación puede cambiarlo
        // hasta que esta transacción termine.
        long balance = ledgerService.balanceOf(account.getId());
        if (balance < amount) {
            throw new InsufficientFundsException();
        }

        Transaction transaction = ledgerService.record(TransactionType.WITHDRAWAL,
                userRepository.getReferenceById(userId), idempotencyKey, Text.blankToNull(request.description()),
                List.of(Posting.debit(account, amount), Posting.credit(accountService.systemAccount(), amount)));

        return MovementResponse.of(transaction, account, amount, balance - amount);
    }

}
