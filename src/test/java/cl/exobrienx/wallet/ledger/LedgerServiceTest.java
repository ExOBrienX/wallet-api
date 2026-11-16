package cl.exobrienx.wallet.ledger;

import cl.exobrienx.wallet.account.Account;
import cl.exobrienx.wallet.user.Role;
import cl.exobrienx.wallet.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T15:30:00Z");

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    private LedgerService ledgerService;
    private User user;
    private Account system;
    private Account account;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(transactionRepository, ledgerEntryRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        user = new User("ana@wallet.cl", "hash", "Ana", "Pérez", "12345678-5", null, Role.USER);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        system = Account.openFor(null, "0000000000", null);
        account = Account.openFor(user, "1234567897", "Principal");
    }

    @Test
    void recordsCompletedTransactionWithAllEntries() {
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        Transaction transaction = ledgerService.record(TransactionType.DEPOSIT, user, "clave-1", "Sueldo",
                List.of(Posting.debit(system, 5_000), Posting.credit(account, 5_000)));

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(transaction.getCompletedAt()).isEqualTo(NOW);
        // Creación, término y asientos comparten el mismo instante del reloj inyectado.
        assertThat(transaction.getCreatedAt()).isEqualTo(NOW);
        assertThat(transaction.getEntries()).allSatisfy(entry -> assertThat(entry.getCreatedAt()).isEqualTo(NOW));
        assertThat(transaction.getDescription()).isEqualTo("Sueldo");
        assertThat(transaction.getEntries())
                .extracting(LedgerEntry::getDirection, LedgerEntry::getAmount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(EntryDirection.DEBIT, 5_000L),
                        org.assertj.core.groups.Tuple.tuple(EntryDirection.CREDIT, 5_000L));
    }

    @Test
    void rejectsUnbalancedPostings() {
        assertThatThrownBy(() -> ledgerService.record(TransactionType.DEPOSIT, user, "clave", null,
                List.of(Posting.debit(system, 5_000), Posting.credit(account, 4_999))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no cuadra");
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsSinglePosting() {
        assertThatThrownBy(() -> LedgerService.validateBalanced(List.of(Posting.credit(account, 100))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveAmounts() {
        assertThatThrownBy(() -> LedgerService.validateBalanced(
                List.of(Posting.debit(system, 0), Posting.credit(account, 0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsSplitPostingsThatBalance() {
        // Un débito puede repartirse en varios créditos (por ejemplo, transferencia con comisión).
        LedgerService.validateBalanced(List.of(
                Posting.debit(account, 1_000), Posting.credit(system, 990), Posting.credit(system, 10)));
    }

    @Test
    void rejectsRepeatedIdempotencyKey() {
        when(transactionRepository.existsByInitiatedByIdAndIdempotencyKey(user.getId(), "repetida")).thenReturn(true);

        assertThatThrownBy(() -> ledgerService.record(TransactionType.DEPOSIT, user, "repetida", null,
                List.of(Posting.debit(system, 100), Posting.credit(account, 100))))
                .isInstanceOf(DuplicateIdempotencyKeyException.class);
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void accountsWithoutEntriesHaveZeroBalance() {
        UUID withMovements = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        when(ledgerEntryRepository.balancesOf(any())).thenReturn(List.of(new AccountBalance(withMovements, 7_500)));

        Map<UUID, Long> balances = ledgerService.balancesOf(List.of(withMovements, empty));

        assertThat(balances).containsEntry(withMovements, 7_500L).containsEntry(empty, 0L);
    }
}
