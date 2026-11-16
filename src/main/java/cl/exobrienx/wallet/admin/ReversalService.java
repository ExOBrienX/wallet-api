package cl.exobrienx.wallet.admin;

import cl.exobrienx.wallet.account.Account;
import cl.exobrienx.wallet.account.AccountService;
import cl.exobrienx.wallet.account.AccountType;
import cl.exobrienx.wallet.admin.dto.ReversalResponse;
import cl.exobrienx.wallet.common.util.Text;
import cl.exobrienx.wallet.ledger.EntryDirection;
import cl.exobrienx.wallet.ledger.LedgerEntry;
import cl.exobrienx.wallet.ledger.LedgerService;
import cl.exobrienx.wallet.ledger.Posting;
import cl.exobrienx.wallet.ledger.Transaction;
import cl.exobrienx.wallet.ledger.TransactionNotFoundException;
import cl.exobrienx.wallet.ledger.TransactionNotReversibleException;
import cl.exobrienx.wallet.ledger.TransactionRepository;
import cl.exobrienx.wallet.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reversas hechas por un administrador (regla 9). Nada se borra ni se modifica: la reversa es
 * una transacción nueva con los asientos inversos, y la original solo cambia su estado.
 * <p>
 * A diferencia de los retiros y transferencias, una reversa se permite aunque deje una cuenta
 * en negativo, como un contracargo: el cliente ya gastó el dinero que ahora se le descuenta.
 * Esa cuenta no podrá enviar dinero hasta volver a saldo positivo, porque la validación normal
 * de saldo lo impide, pero sí puede recibir.
 */
@Service
public class ReversalService {

    private static final Logger log = LoggerFactory.getLogger(ReversalService.class);

    private final TransactionRepository transactionRepository;
    private final AccountService accountService;
    private final UserRepository userRepository;
    private final LedgerService ledgerService;

    public ReversalService(TransactionRepository transactionRepository, AccountService accountService,
                           UserRepository userRepository, LedgerService ledgerService) {
        this.transactionRepository = transactionRepository;
        this.accountService = accountService;
        this.userRepository = userRepository;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public ReversalResponse reverse(UUID transactionId, UUID adminId, String reason) {
        // 1. Bloquear la transacción: una segunda reversa simultánea espera aquí y luego la ve REVERSED.
        Transaction original = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(TransactionNotFoundException::new);
        if (!original.isReversible()) {
            throw new TransactionNotReversibleException(original);
        }

        // 2. Bloquear las cuentas de cliente, con el mismo orden que usan las transferencias.
        //    La cuenta del sistema no se bloquea (ver AccountService#systemAccount).
        List<UUID> clientAccountIds = original.getEntries().stream()
                .map(LedgerEntry::getAccount)
                .filter(account -> account.getType() == AccountType.USER)
                .map(Account::getId)
                .toList();
        Map<UUID, Account> locked = accountService.lockInOrder(clientAccountIds);

        // 3. Asientos inversos: mismas cuentas y montos, dirección contraria.
        List<Posting> inverse = original.getEntries().stream()
                .map(entry -> new Posting(entry.getAccount(), opposite(entry.getDirection()), entry.getAmount()))
                .toList();
        String cleanReason = Text.blankToNull(reason);
        String description = "Reversa de " + original.getType().name().toLowerCase()
                + (cleanReason == null ? "" : ": " + cleanReason);

        Transaction reversal = ledgerService.recordReversal(original, userRepository.getReferenceById(adminId),
                description, inverse);

        List<ReversalResponse.AffectedAccount> affected = locked.values().stream()
                .map(account -> ReversalResponse.AffectedAccount.of(account.getId(), account.getAccountNumber(),
                        ledgerService.balanceOf(account.getId())))
                .toList();
        affected.stream().filter(ReversalResponse.AffectedAccount::negative).forEach(account ->
                log.warn("La reversa {} dejó la cuenta {} con saldo negativo: {}",
                        reversal.getId(), account.accountNumber(), account.balanceAfter()));

        return new ReversalResponse(reversal.getId(), original.getId(), original.getType(), description,
                affected, reversal.getCreatedAt());
    }

    private static EntryDirection opposite(EntryDirection direction) {
        return direction == EntryDirection.DEBIT ? EntryDirection.CREDIT : EntryDirection.DEBIT;
    }
}
