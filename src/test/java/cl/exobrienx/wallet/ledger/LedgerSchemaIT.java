package cl.exobrienx.wallet.ledger;

import cl.exobrienx.wallet.TestcontainersConfiguration;
import cl.exobrienx.wallet.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica las protecciones contables que la migración V1 pone en la base de datos.
 * Usa SQL directo a propósito: prueba que la base las cumple aunque la aplicación fallara.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LedgerSchemaIT {

    private static final UUID SYSTEM_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, first_name, last_name, rut, role)
                VALUES (?, ?, 'x', 'Ana', 'Pérez', ?, 'USER')""", userId, userId + "@test.cl", TestData.randomRut());
        jdbc.update("""
                INSERT INTO accounts (id, user_id, account_number, currency, status, type)
                VALUES (?, ?, ?, 'CLP', 'ACTIVE', 'USER')""", accountId, userId, TestData.randomAccountNumber());
    }

    @Test
    void systemAccountIsCreatedByMigration() {
        String type = jdbc.queryForObject("SELECT type FROM accounts WHERE id = ?", String.class, SYSTEM_ACCOUNT);
        assertThat(type).isEqualTo("SYSTEM");
    }

    @Test
    void balancedTransactionIsCommitted() {
        UUID txId = depositOf(5_000);

        Long balance = jdbc.queryForObject("""
                SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END)
                FROM ledger_entries WHERE account_id = ?""", Long.class, accountId);
        assertThat(balance).isEqualTo(5_000L);
        assertThat(entriesOf(txId)).isEqualTo(2);
    }

    @Test
    void unbalancedTransactionIsRejectedAtCommit() {
        UUID txId = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            insertTransaction(txId);
            insertEntry(txId, SYSTEM_ACCOUNT, 5_000, "DEBIT");
            insertEntry(txId, accountId, 4_000, "CREDIT"); // no cuadra: faltan 1.000
        })).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("no cuadra");

        // Toda la transacción se deshizo, no solo el último asiento.
        assertThat(entriesOf(txId)).isZero();
    }

    @Test
    void transactionWithSingleEntryIsRejected() {
        UUID txId = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            insertTransaction(txId);
            insertEntry(txId, accountId, 5_000, "CREDIT");
        })).isInstanceOf(DataAccessException.class);
    }

    @Test
    void nonPositiveAmountIsRejected() {
        UUID txId = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            insertTransaction(txId);
            insertEntry(txId, SYSTEM_ACCOUNT, 0, "DEBIT");
        })).isInstanceOf(DataAccessException.class);
    }

    @Test
    void ledgerEntriesCannotBeUpdatedOrDeleted() {
        UUID txId = depositOf(1_000);

        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = 999 WHERE transaction_id = ?", txId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("inmutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE transaction_id = ?", txId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("inmutable");
    }

    @Test
    void onlyTransactionStatusCanChange() {
        UUID txId = depositOf(1_000);

        jdbc.update("UPDATE transactions SET status = 'REVERSED' WHERE id = ?", txId);
        assertThat(jdbc.queryForObject("SELECT status FROM transactions WHERE id = ?", String.class, txId))
                .isEqualTo("REVERSED");

        assertThatThrownBy(() -> jdbc.update("UPDATE transactions SET type = 'WITHDRAWAL' WHERE id = ?", txId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM transactions WHERE id = ?", txId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void idempotencyKeyIsUniquePerUser() {
        String key = "key-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transactions (id, type, status, idempotency_key, initiated_by)
                VALUES (?, 'DEPOSIT', 'PENDING', ?, ?)""", UUID.randomUUID(), key, userId);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO transactions (id, type, status, idempotency_key, initiated_by)
                VALUES (?, 'DEPOSIT', 'PENDING', ?, ?)""", UUID.randomUUID(), key, userId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void clientWithoutRutIsRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO users (id, email, password_hash, first_name, last_name, role)
                VALUES (?, ?, 'x', 'Sin', 'Rut', 'USER')""", UUID.randomUUID(), UUID.randomUUID() + "@test.cl"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void completedTransactionRequiresCompletionDate() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO transactions (id, type, status, idempotency_key, initiated_by)
                VALUES (?, 'DEPOSIT', 'COMPLETED', ?, ?)""", UUID.randomUUID(), "k-" + UUID.randomUUID(), userId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void transactionDescriptionIsImmutable() {
        UUID txId = depositOf(1_000);

        assertThatThrownBy(() -> jdbc.update("UPDATE transactions SET description = 'otra' WHERE id = ?", txId))
                .isInstanceOf(DataAccessException.class);
    }

    // --- helpers -------------------------------------------------------------

    /** Depósito válido: débito a SYSTEM, crédito a la cuenta del usuario. */
    private UUID depositOf(long amount) {
        UUID txId = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            insertTransaction(txId);
            insertEntry(txId, SYSTEM_ACCOUNT, amount, "DEBIT");
            insertEntry(txId, accountId, amount, "CREDIT");
        });
        return txId;
    }

    private void insertTransaction(UUID txId) {
        jdbc.update("""
                INSERT INTO transactions (id, type, status, idempotency_key, initiated_by, completed_at)
                VALUES (?, 'DEPOSIT', 'COMPLETED', ?, ?, now())""", txId, txId.toString(), userId);
    }

    private void insertEntry(UUID txId, UUID account, long amount, String direction) {
        jdbc.update("""
                INSERT INTO ledger_entries (id, transaction_id, account_id, amount, direction)
                VALUES (?, ?, ?, ?, ?)""", UUID.randomUUID(), txId, account, amount, direction);
    }

    private int entriesOf(UUID txId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = ?",
                Integer.class, txId);
        return count == null ? 0 : count;
    }
}
