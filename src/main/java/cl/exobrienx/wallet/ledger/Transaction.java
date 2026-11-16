package cl.exobrienx.wallet.ledger;

import cl.exobrienx.wallet.account.Account;
import cl.exobrienx.wallet.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Operación del libro contable: agrupa los asientos que se registran juntos. Una vez
 * guardada, solo puede cambiar su estado (lo garantiza también un trigger en la base).
 */
@Entity
@Table(name = "transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12, updatable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionStatus status;

    @Column(name = "idempotency_key", length = 100, updatable = false)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiated_by", nullable = false, updatable = false)
    private User initiatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reverses_transaction_id", updatable = false)
    private Transaction reversesTransaction;

    @Column(length = 140, updatable = false)
    private String description;

    /**
     * Se asigna desde el {@code Clock} de la aplicación (no con {@code @CreationTimestamp}), para
     * que la transacción, sus asientos y su fecha de término usen el mismo reloj y el mismo instante.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST)
    private List<LedgerEntry> entries = new ArrayList<>();

    /** Transacción que se crea y completa en el mismo instante (todas las operaciones actuales). */
    static Transaction completed(TransactionType type, User initiatedBy, String idempotencyKey,
                                 String description, Instant now) {
        Transaction transaction = new Transaction();
        transaction.type = type;
        transaction.status = TransactionStatus.COMPLETED;
        transaction.initiatedBy = initiatedBy;
        transaction.idempotencyKey = idempotencyKey;
        transaction.description = description;
        transaction.createdAt = now;
        transaction.completedAt = now;
        return transaction;
    }

    /** Reversa de {@code original}: transacción nueva, completada, que apunta a la original. */
    static Transaction reversalOf(Transaction original, User admin, String description, Instant now) {
        Transaction reversal = completed(TransactionType.REVERSAL, admin, null, description, now);
        reversal.reversesTransaction = original;
        return reversal;
    }

    /** Una transacción se puede revertir si está completada y no es ella misma una reversa. */
    public boolean isReversible() {
        return status == TransactionStatus.COMPLETED && type != TransactionType.REVERSAL;
    }

    /** Único cambio permitido sobre una transacción guardada (lo exige también un trigger). */
    void markReversed() {
        this.status = TransactionStatus.REVERSED;
    }

    void addEntry(Account account, EntryDirection direction, long amount) {
        entries.add(new LedgerEntry(this, account, direction, amount, createdAt));
    }

    public List<LedgerEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
