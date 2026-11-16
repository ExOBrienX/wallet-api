package cl.exobrienx.wallet.ledger;

import cl.exobrienx.wallet.user.User;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Núcleo contable: el único lugar donde se registran movimientos de dinero.
 * <p>
 * Cada operación se guarda como una {@link Transaction} con sus asientos, y siempre cuadra:
 * la suma de los débitos es igual a la suma de los créditos. Este servicio no valida reglas
 * de negocio (saldo suficiente, cuenta activa, límites); eso corresponde a quien lo llama,
 * después de bloquear las cuentas involucradas.
 */
@Service
public class LedgerService {

    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final Clock clock;

    public LedgerService(TransactionRepository transactionRepository,
                         LedgerEntryRepository ledgerEntryRepository,
                         Clock clock) {
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.clock = clock;
    }

    /**
     * Registra una transacción completada con sus asientos.
     * <p>
     * {@code MANDATORY} exige que ya exista una transacción de base de datos abierta: el que
     * llama debe haber bloqueado las cuentas y validado las reglas dentro de esa misma
     * transacción. Si se llamara sin una, fallaría de inmediato en vez de registrar el
     * movimiento sin protección.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Transaction record(TransactionType type, User initiatedBy, String idempotencyKey,
                              String description, List<Posting> postings) {
        validateBalanced(postings);
        if (idempotencyKey != null
                && transactionRepository.existsByInitiatedByIdAndIdempotencyKey(initiatedBy.getId(), idempotencyKey)) {
            throw new DuplicateIdempotencyKeyException();
        }

        Transaction transaction = Transaction.completed(type, initiatedBy, idempotencyKey, description, clock.instant());
        postings.forEach(p -> transaction.addEntry(p.account(), p.direction(), p.amount()));
        try {
            // El flush ejecuta los INSERT aquí, para traducir una clave duplicada concurrente.
            return transactionRepository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("uq_transactions_idempotency")) {
                throw new DuplicateIdempotencyKeyException();
            }
            throw e;
        }
    }

    /**
     * Registra la reversa de {@code original} con los asientos indicados (los inversos de los
     * originales) y marca la original como REVERSED. Quien llama debe haber bloqueado la
     * transacción original y las cuentas involucradas.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Transaction recordReversal(Transaction original, User admin, String description, List<Posting> postings) {
        validateBalanced(postings);
        Transaction reversal = Transaction.reversalOf(original, admin, description, clock.instant());
        postings.forEach(p -> reversal.addEntry(p.account(), p.direction(), p.amount()));
        original.markReversed();
        return transactionRepository.saveAndFlush(reversal);
    }

    @Transactional(readOnly = true)
    public long balanceOf(UUID accountId) {
        return ledgerEntryRepository.balanceOf(accountId);
    }

    @Transactional(readOnly = true)
    public Page<AccountMovement> movementsOf(UUID accountId, Instant from, Instant to, Pageable pageable) {
        return ledgerEntryRepository.movementsOf(accountId, from, to, pageable);
    }

    @Transactional(readOnly = true)
    public long transferredSince(UUID accountId, Instant since) {
        return ledgerEntryRepository.transferredSince(accountId, since);
    }

    /** Saldos de varias cuentas; las que no tienen movimientos quedan con saldo cero. */
    @Transactional(readOnly = true)
    public Map<UUID, Long> balancesOf(Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> found = ledgerEntryRepository.balancesOf(accountIds).stream()
                .collect(Collectors.toMap(AccountBalance::accountId, AccountBalance::balance));
        return accountIds.stream().collect(Collectors.toMap(id -> id, id -> found.getOrDefault(id, 0L)));
    }

    /** Regla 1 del libro: al menos dos asientos, montos positivos y débitos = créditos. */
    static void validateBalanced(List<Posting> postings) {
        if (postings.size() < 2) {
            throw new IllegalArgumentException("Una transacción necesita al menos dos asientos");
        }
        long debits = 0;
        long credits = 0;
        for (Posting posting : postings) {
            if (posting.amount() <= 0) {
                throw new IllegalArgumentException("Los montos de los asientos deben ser positivos");
            }
            if (posting.direction() == EntryDirection.DEBIT) {
                debits = Math.addExact(debits, posting.amount());
            } else {
                credits = Math.addExact(credits, posting.amount());
            }
        }
        if (debits != credits) {
            throw new IllegalArgumentException(
                    "La transacción no cuadra: débitos " + debits + ", créditos " + credits);
        }
    }
}
