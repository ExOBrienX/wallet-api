package cl.exobrienx.wallet.account;

import cl.exobrienx.wallet.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Cuenta de la billetera. No tiene campo de saldo: el saldo se calcula siempre desde los
 * asientos del libro contable.
 */
@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    /** Cuenta interna contra la que se registran depósitos y retiros (creada en la migración V1). */
    public static final UUID SYSTEM_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public static final String CURRENCY_CLP = "CLP";

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /** Dueño de la cuenta; nulo en las cuentas del sistema. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", updatable = false)
    private User user;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "account_number", nullable = false, unique = true, length = 10, updatable = false)
    private String accountNumber;

    @Column(length = 40)
    private String alias;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccountStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private AccountType type;

    /**
     * Bloqueo optimista: si dos operaciones modifican la misma cuenta a la vez (por ejemplo,
     * dos administradores cambiando su estado), la segunda falla en vez de pisar a la primera.
     */
    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Account openFor(User owner, String accountNumber, String alias) {
        Account account = new Account();
        account.user = owner;
        account.accountNumber = accountNumber;
        account.alias = alias;
        account.currency = CURRENCY_CLP;
        account.status = AccountStatus.ACTIVE;
        account.type = AccountType.USER;
        return account;
    }

    public void changeStatus(AccountStatus newStatus) {
        this.status = newStatus;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public boolean isOwnedBy(UUID userId) {
        return user != null && user.getId().equals(userId);
    }
}
