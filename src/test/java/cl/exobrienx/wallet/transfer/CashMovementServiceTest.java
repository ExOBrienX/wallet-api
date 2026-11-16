package cl.exobrienx.wallet.transfer;

import cl.exobrienx.wallet.account.Account;
import cl.exobrienx.wallet.account.AccountBlockedException;
import cl.exobrienx.wallet.account.AccountService;
import cl.exobrienx.wallet.ledger.EntryDirection;
import cl.exobrienx.wallet.ledger.LedgerService;
import cl.exobrienx.wallet.ledger.Posting;
import cl.exobrienx.wallet.ledger.Transaction;
import cl.exobrienx.wallet.ledger.TransactionType;
import cl.exobrienx.wallet.transfer.dto.CashMovementRequest;
import cl.exobrienx.wallet.user.Role;
import cl.exobrienx.wallet.user.User;
import cl.exobrienx.wallet.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashMovementServiceTest {

    @Mock
    private AccountService accountService;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private UserRepository userRepository;

    private CashMovementService service;
    private UUID userId;
    private Account account;
    private Account system;

    @BeforeEach
    void setUp() {
        service = new CashMovementService(accountService, ledgerService, userRepository);
        User user = new User("ana@wallet.cl", "hash", "Ana", "Pérez", "12345678-5", null, Role.USER);
        userId = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", userId);
        account = Account.openFor(user, "1234567897", null);
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        system = Account.openFor(null, "0000000000", null);
        when(accountService.lockOwnActiveAccount(account.getId(), userId)).thenReturn(account);
    }

    @Test
    void depositCreditsAccountAndDebitsSystem() {
        when(accountService.systemAccount()).thenReturn(system);
        when(userRepository.getReferenceById(userId)).thenReturn(account.getUser());
        when(ledgerService.record(any(), any(), any(), any(), anyList())).thenReturn(mock(Transaction.class));

        service.deposit(userId, "clave", new CashMovementRequest(account.getId(), 10_000L, "Sueldo"));

        List<Posting> postings = capturePostings(TransactionType.DEPOSIT);
        assertThat(postings).containsExactly(Posting.debit(system, 10_000), Posting.credit(account, 10_000));
    }

    @Test
    void withdrawalDebitsAccountWhenBalanceIsEnough() {
        when(ledgerService.balanceOf(account.getId())).thenReturn(10_000L);
        when(accountService.systemAccount()).thenReturn(system);
        when(userRepository.getReferenceById(userId)).thenReturn(account.getUser());
        when(ledgerService.record(any(), any(), any(), any(), anyList())).thenReturn(mock(Transaction.class));

        var response = service.withdraw(userId, "clave", new CashMovementRequest(account.getId(), 10_000L, null));

        List<Posting> postings = capturePostings(TransactionType.WITHDRAWAL);
        assertThat(postings.getFirst().direction()).isEqualTo(EntryDirection.DEBIT);
        assertThat(postings.getFirst().account()).isSameAs(account);
        assertThat(response.balanceAfter()).isZero();
    }

    @Test
    void withdrawalWithInsufficientFundsRecordsNothing() {
        when(ledgerService.balanceOf(account.getId())).thenReturn(9_999L);

        assertThatThrownBy(() -> service.withdraw(userId, "clave",
                new CashMovementRequest(account.getId(), 10_000L, null)))
                .isInstanceOf(InsufficientFundsException.class);
        verify(ledgerService, never()).record(any(), any(), any(), any(), anyList());
    }

    @Test
    void blockedAccountCannotOperate() {
        when(accountService.lockOwnActiveAccount(account.getId(), userId))
                .thenThrow(new AccountBlockedException(account.getAccountNumber()));

        assertThatThrownBy(() -> service.deposit(userId, "clave",
                new CashMovementRequest(account.getId(), 1_000L, null)))
                .isInstanceOf(AccountBlockedException.class);
        verify(ledgerService, never()).record(any(), any(), any(), any(), anyList());
    }

    @SuppressWarnings("unchecked")
    private List<Posting> capturePostings(TransactionType expectedType) {
        ArgumentCaptor<List<Posting>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).record(eq(expectedType), any(), eq("clave"), any(), captor.capture());
        return captor.getValue();
    }
}
