package cl.exobrienx.wallet.account;

import cl.exobrienx.wallet.common.config.LimitsProperties;
import cl.exobrienx.wallet.common.security.CurrentUser;
import cl.exobrienx.wallet.ledger.LedgerService;
import cl.exobrienx.wallet.user.Role;
import cl.exobrienx.wallet.user.User;
import cl.exobrienx.wallet.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private AccountNumberGenerator numberGenerator;

    private AccountService service;
    private User owner;

    @BeforeEach
    void setUp() {
        LimitsProperties limits = new LimitsProperties(1_000_000, ZoneId.of("America/Santiago"), 5);
        service = new AccountService(accountRepository, userRepository, ledgerService, numberGenerator, limits);
        owner = new User("ana@wallet.cl", "hash", "Ana", "Pérez", "12345678-5", null, Role.USER);
        ReflectionTestUtils.setField(owner, "id", UUID.randomUUID());
    }

    @Test
    void locksAccountsInIdOrderRegardlessOfArgumentOrder() {
        Account a = account();
        Account b = account();
        Account lower = a.getId().compareTo(b.getId()) < 0 ? a : b;
        Account higher = lower == a ? b : a;
        when(accountRepository.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));
        when(accountRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));

        // Una transferencia de A a B y otra de B a A piden los bloqueos en orden distinto...
        Map<UUID, Account> first = service.lockInOrder(List.of(a.getId(), b.getId()));
        Map<UUID, Account> second = service.lockInOrder(List.of(b.getId(), a.getId()));

        // ...pero ambas bloquean primero la misma cuenta.
        InOrder order = inOrder(accountRepository);
        order.verify(accountRepository).findByIdForUpdate(lower.getId());
        order.verify(accountRepository).findByIdForUpdate(higher.getId());
        order.verify(accountRepository).findByIdForUpdate(lower.getId());
        order.verify(accountRepository).findByIdForUpdate(higher.getId());
        assertThat(first).containsOnlyKeys(a.getId(), b.getId());
        assertThat(second).containsOnlyKeys(a.getId(), b.getId());
    }

    @Test
    void lockingSameAccountTwiceLocksItOnce() {
        Account a = account();
        when(accountRepository.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));

        assertThat(service.lockInOrder(List.of(a.getId(), a.getId()))).hasSize(1);
    }

    @Test
    void systemAccountIsNotAClientAccount() {
        Account system = Account.openFor(null, "0000000000", null);
        ReflectionTestUtils.setField(system, "type", AccountType.SYSTEM);
        when(accountRepository.findByAccountNumber("0000000000")).thenReturn(Optional.of(system));

        assertThat(service.findClientAccountByNumber("0000000000")).isEmpty();
    }

    @Test
    void cannotOperateOnAccountOfAnotherUser() {
        Account a = account();
        when(accountRepository.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.lockOwnActiveAccount(a.getId(), UUID.randomUUID()))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void blockedAccountCannotOperate() {
        Account a = account();
        a.changeStatus(AccountStatus.BLOCKED);
        when(accountRepository.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.lockOwnActiveAccount(a.getId(), owner.getId()))
                .isInstanceOf(AccountBlockedException.class);
    }

    @Test
    void accountLimitPerUserIsEnforced() {
        when(accountRepository.countByUserId(owner.getId())).thenReturn(5L);

        assertThatThrownBy(() -> service.open(owner.getId(), "Ahorro"))
                .isInstanceOf(AccountLimitReachedException.class);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void otherUsersCannotSeeAccountButAdminCan() {
        Account a = account();
        when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.get(a.getId(), new CurrentUser(UUID.randomUUID(), false)))
                .isInstanceOf(AccountNotFoundException.class);
        assertThat(service.get(a.getId(), new CurrentUser(UUID.randomUUID(), true)).id()).isEqualTo(a.getId());
    }

    private Account account() {
        Account account = Account.openFor(owner, "1234567897", null);
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        return account;
    }
}
