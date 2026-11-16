package cl.exobrienx.wallet.transfer;

import cl.exobrienx.wallet.account.Account;
import cl.exobrienx.wallet.account.AccountBlockedException;
import cl.exobrienx.wallet.account.AccountNotFoundException;
import cl.exobrienx.wallet.account.AccountNumberGenerator;
import cl.exobrienx.wallet.account.AccountService;
import cl.exobrienx.wallet.common.config.LimitsProperties;
import cl.exobrienx.wallet.common.util.Text;
import cl.exobrienx.wallet.ledger.LedgerService;
import cl.exobrienx.wallet.ledger.Posting;
import cl.exobrienx.wallet.ledger.Transaction;
import cl.exobrienx.wallet.ledger.TransactionType;
import cl.exobrienx.wallet.transfer.dto.TransferRequest;
import cl.exobrienx.wallet.transfer.dto.TransferResponse;
import cl.exobrienx.wallet.user.User;
import cl.exobrienx.wallet.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transferencias entre cuentas. Todo ocurre en una sola transacción: o se registran ambos
 * asientos (débito en origen, crédito en destino) o ninguno.
 */
@Service
public class TransferService {

    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final UserRepository userRepository;
    private final LimitsProperties limits;
    private final Clock clock;

    public TransferService(AccountService accountService, LedgerService ledgerService,
                           UserRepository userRepository, LimitsProperties limits, Clock clock) {
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.userRepository = userRepository;
        this.limits = limits;
        this.clock = clock;
    }

    @Transactional
    public TransferResponse transfer(UUID userId, String idempotencyKey, TransferRequest request) {
        if (!AccountNumberGenerator.isValid(request.destinationAccountNumber())) {
            throw new InvalidAccountNumberException();
        }
        UUID sourceId = request.sourceAccountId();
        UUID destinationId = accountService.findClientAccountByNumber(request.destinationAccountNumber())
                .map(Account::getId)
                .orElseThrow(DestinationAccountNotFoundException::new);
        if (destinationId.equals(sourceId)) {
            throw new SameAccountTransferException();
        }

        // Ambas cuentas quedan bloqueadas, en orden fijo, hasta el fin de la transacción.
        Map<UUID, Account> locked = accountService.lockInOrder(List.of(sourceId, destinationId));
        Account source = locked.get(sourceId);
        Account destination = locked.get(destinationId);

        // Las validaciones van después del bloqueo: nada de lo que se revisa puede cambiar
        // hasta que esta transacción termine.
        if (!source.isOwnedBy(userId)) {
            throw new AccountNotFoundException();
        }
        if (!source.isActive()) {
            throw new AccountBlockedException(source.getAccountNumber());
        }
        if (!destination.isActive()) {
            throw new DestinationAccountUnavailableException();
        }

        long amount = request.amount();
        long balance = ledgerService.balanceOf(source.getId());
        if (balance < amount) {
            throw new InsufficientFundsException();
        }
        checkDailyLimit(source.getId(), amount);

        Transaction transaction = ledgerService.record(TransactionType.TRANSFER,
                userRepository.getReferenceById(userId), idempotencyKey, Text.blankToNull(request.description()),
                List.of(Posting.debit(source, amount), Posting.credit(destination, amount)));

        return TransferResponse.of(transaction, source.getId(), source.getAccountNumber(),
                destination.getAccountNumber(), maskedName(destination.getUser()), amount, balance - amount);
    }

    /**
     * Regla 5: lo transferido hoy más este monto no puede superar el límite diario. El "día" es
     * el día calendario en la zona horaria configurada (Chile), no en UTC: en UTC el día
     * cambiaría a las 21:00 o 22:00 hora chilena.
     */
    private void checkDailyLimit(UUID sourceId, long amount) {
        LocalDate today = LocalDate.now(clock.withZone(limits.timezone()));
        Instant startOfDay = today.atStartOfDay(limits.timezone()).toInstant();
        long alreadyTransferred = ledgerService.transferredSince(sourceId, startOfDay);
        long remaining = Math.max(0, limits.dailyTransfer() - alreadyTransferred);
        if (amount > remaining) {
            throw new DailyTransferLimitExceededException(limits.dailyTransfer(), remaining);
        }
    }

    /** "Matías González Pérez" → "Matías G." */
    static String maskedName(User user) {
        String lastName = user.getLastName().trim();
        return lastName.isEmpty() ? user.getFirstName() : user.getFirstName() + " " + lastName.charAt(0) + ".";
    }
}
