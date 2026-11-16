package cl.exobrienx.wallet.account;

import cl.exobrienx.wallet.account.dto.AccountResponse;
import cl.exobrienx.wallet.common.config.LimitsProperties;
import cl.exobrienx.wallet.common.security.CurrentUser;
import cl.exobrienx.wallet.common.util.Text;
import cl.exobrienx.wallet.common.web.PageResponse;
import cl.exobrienx.wallet.ledger.AccountMovement;
import cl.exobrienx.wallet.ledger.LedgerService;
import cl.exobrienx.wallet.user.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountService {

    /** Reintentos ante una colisión de número de cuenta (con 900 millones de opciones, casi imposible). */
    private static final int MAX_NUMBER_ATTEMPTS = 5;

    /** Máxima posición recorrible en el historial (10.000 páginas de 100 movimientos). */
    static final long MAX_HISTORY_OFFSET = 1_000_000;

    /** Límite superior cuando no se indica "to" (PostgreSQL no admite Instant.MAX). */
    private static final Instant NO_UPPER_BOUND = Instant.parse("3000-01-01T00:00:00Z");

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final LedgerService ledgerService;
    private final AccountNumberGenerator numberGenerator;
    private final LimitsProperties limits;

    public AccountService(AccountRepository accountRepository,
                          UserRepository userRepository,
                          LedgerService ledgerService,
                          AccountNumberGenerator numberGenerator,
                          LimitsProperties limits) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.ledgerService = ledgerService;
        this.numberGenerator = numberGenerator;
        this.limits = limits;
    }

    @Transactional
    public AccountResponse open(UUID userId, String alias) {
        if (accountRepository.countByUserId(userId) >= limits.maxAccountsPerUser()) {
            throw new AccountLimitReachedException(limits.maxAccountsPerUser());
        }
        Account account = Account.openFor(userRepository.getReferenceById(userId), uniqueAccountNumber(), Text.blankToNull(alias));
        return AccountResponse.from(accountRepository.save(account), 0);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listOwn(UUID userId) {
        List<Account> accounts = accountRepository.findByUserIdOrderByCreatedAtAsc(userId);
        Map<UUID, Long> balances = ledgerService.balancesOf(accounts.stream().map(Account::getId).toList());
        return accounts.stream()
                .map(account -> AccountResponse.from(account, balances.get(account.getId())))
                .toList();
    }

    /** Detalle de una cuenta. Un cliente solo ve las suyas; un administrador ve cualquiera. */
    @Transactional(readOnly = true)
    public AccountResponse get(UUID accountId, CurrentUser currentUser) {
        Account account = accountRepository.findById(accountId)
                .filter(a -> currentUser.admin() || a.isOwnedBy(currentUser.id()))
                .orElseThrow(AccountNotFoundException::new);
        return AccountResponse.from(account, ledgerService.balanceOf(account.getId()));
    }

    /**
     * Historial de movimientos de una cuenta, del más reciente al más antiguo. Un cliente solo
     * ve el de sus cuentas; un administrador, el de cualquiera.
     *
     * @param from inicio del rango (incluido); sin valor, desde el primer movimiento
     * @param to   fin del rango (excluido); sin valor, hasta el último movimiento
     */
    @Transactional(readOnly = true)
    public PageResponse<AccountMovement> movements(UUID accountId, CurrentUser currentUser,
                                                   Instant from, Instant to, Pageable pageable) {
        Instant start = from != null ? from : Instant.EPOCH;
        Instant end = to != null ? to : NO_UPPER_BOUND;
        if (!start.isBefore(end)) {
            throw new InvalidDateRangeException();
        }
        accountRepository.findById(accountId)
                .filter(a -> currentUser.admin() || a.isOwnedBy(currentUser.id()))
                .orElseThrow(AccountNotFoundException::new);
        // Sin este límite, una página enorme (ej. page=2147483647) desborda la posición de inicio
        // de la consulta. Además, recorrer tan lejos por posición es lento: para eso están las fechas.
        if (pageable.getOffset() > MAX_HISTORY_OFFSET) {
            throw new PageOutOfRangeException(MAX_HISTORY_OFFSET);
        }
        // El orden lo define la consulta; se ignora cualquier "sort" que envíe el cliente.
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return PageResponse.from(ledgerService.movementsOf(accountId, start, end, unsorted));
    }

    /**
     * Carga y bloquea una cuenta propia y activa, para operar sobre ella. Debe llamarse dentro
     * de una transacción: el bloqueo dura hasta que esta termina.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Account lockOwnActiveAccount(UUID accountId, UUID userId) {
        Account account = accountRepository.findByIdForUpdate(accountId)
                .filter(a -> a.isOwnedBy(userId))
                .orElseThrow(AccountNotFoundException::new);
        if (!account.isActive()) {
            throw new AccountBlockedException(account.getAccountNumber());
        }
        return account;
    }

    /**
     * Bloquea varias cuentas siempre en el mismo orden (el de {@link UUID#compareTo}).
     * <p>
     * Si A transfiere a B mientras B transfiere a A, ambas transacciones intentan bloquear
     * primero la misma cuenta: una espera a la otra en vez de quedar cada una esperando la
     * cuenta que tiene la otra (deadlock). El orden concreto da igual; lo importante es que
     * todo el código que bloquee más de una cuenta pase por aquí y use el mismo.
     *
     * @return las cuentas bloqueadas, por id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Account> lockInOrder(Collection<UUID> accountIds) {
        Map<UUID, Account> locked = new LinkedHashMap<>();
        accountIds.stream().distinct().sorted().forEach(id -> locked.put(id,
                accountRepository.findByIdForUpdate(id).orElseThrow(AccountNotFoundException::new)));
        return locked;
    }

    /** Cuenta de cliente con ese número (sin bloquearla). La cuenta del sistema no se considera. */
    @Transactional(readOnly = true)
    public Optional<Account> findClientAccountByNumber(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .filter(account -> account.getType() == AccountType.USER);
    }

    /**
     * Referencia a la cuenta del sistema, sin bloquearla. Todos los depósitos y retiros pasan
     * por ella: si se bloqueara, las operaciones de todos los usuarios quedarían en fila.
     * Como su saldo no se valida (es negativo por diseño), no hace falta el bloqueo.
     */
    public Account systemAccount() {
        return accountRepository.getReferenceById(Account.SYSTEM_ACCOUNT_ID);
    }

    private String uniqueAccountNumber() {
        for (int attempt = 0; attempt < MAX_NUMBER_ATTEMPTS; attempt++) {
            String candidate = numberGenerator.generate();
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No se pudo generar un número de cuenta único");
    }
}
