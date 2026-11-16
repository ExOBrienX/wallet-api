package cl.exobrienx.wallet.transfer;

import cl.exobrienx.wallet.account.Account;
import cl.exobrienx.wallet.account.AccountBlockedException;
import cl.exobrienx.wallet.account.AccountNotFoundException;
import cl.exobrienx.wallet.account.AccountService;
import cl.exobrienx.wallet.account.AccountStatus;
import cl.exobrienx.wallet.common.config.LimitsProperties;
import cl.exobrienx.wallet.ledger.LedgerService;
import cl.exobrienx.wallet.ledger.Posting;
import cl.exobrienx.wallet.ledger.Transaction;
import cl.exobrienx.wallet.ledger.TransactionType;
import cl.exobrienx.wallet.transfer.dto.TransferRequest;
import cl.exobrienx.wallet.transfer.dto.TransferResponse;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    /** 02:30 UTC del 10 de marzo = 23:30 del 9 de marzo en Chile (UTC-3 en horario de verano). */
    private static final Instant NOW = Instant.parse("2026-03-10T02:30:00Z");
    private static final Instant START_OF_CHILEAN_DAY = Instant.parse("2026-03-09T03:00:00Z");
    private static final long DAILY_LIMIT = 1_000_000;

    @Mock
    private AccountService accountService;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private UserRepository userRepository;

    private TransferService service;
    private User ana;
    private Account source;
    private Account destination;

    @BeforeEach
    void setUp() {
        LimitsProperties limits = new LimitsProperties(DAILY_LIMIT, ZoneId.of("America/Santiago"), 5);
        service = new TransferService(accountService, ledgerService, userRepository, limits,
                Clock.fixed(NOW, ZoneOffset.UTC));

        ana = user("Ana", "Pérez Soto");
        User matias = user("Matías", "González Pérez");
        source = account(ana, "1234567897");
        destination = account(matias, "7992739875");
    }

    @Test
    void transferDebitsSourceAndCreditsDestination() {
        stubLocks();
        when(ledgerService.balanceOf(source.getId())).thenReturn(50_000L);
        when(userRepository.getReferenceById(ana.getId())).thenReturn(ana);
        when(ledgerService.record(any(), any(), any(), any(), anyList())).thenReturn(mock(Transaction.class));

        TransferResponse response = service.transfer(ana.getId(), "clave", request(20_000));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Posting>> postings = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).record(eq(TransactionType.TRANSFER), eq(ana), eq("clave"), any(), postings.capture());
        assertThat(postings.getValue()).containsExactly(Posting.debit(source, 20_000), Posting.credit(destination, 20_000));
        assertThat(response.balanceAfter()).isEqualTo(30_000);
        assertThat(response.recipientName()).isEqualTo("Matías G.");
    }

    @Test
    void cannotTransferToSameAccount() {
        when(accountService.findClientAccountByNumber(source.getAccountNumber())).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service.transfer(ana.getId(), "clave",
                new TransferRequest(source.getId(), source.getAccountNumber(), 1_000L, null)))
                .isInstanceOf(SameAccountTransferException.class);
        verify(accountService, never()).lockInOrder(any());
    }

    @Test
    void locksBothAccountsThroughAccountService() {
        stubLocks();
        when(ledgerService.balanceOf(source.getId())).thenReturn(0L);

        assertThatThrownBy(() -> service.transfer(ana.getId(), "clave", request(1_000)))
                .isInstanceOf(InsufficientFundsException.class);

        // El orden lo garantiza AccountService.lockInOrder (ver AccountServiceTest).
        verify(accountService).lockInOrder(List.of(source.getId(), destination.getId()));
    }

    @Test
    void rejectsNumberWithWrongCheckDigit() {
        assertThatThrownBy(() -> service.transfer(ana.getId(), "clave",
                new TransferRequest(source.getId(), "7992739876", 1_000L, null)))
                .isInstanceOf(InvalidAccountNumberException.class);
    }

    @Test
    void unknownOrSystemDestinationIsNotFound() {
        when(accountService.findClientAccountByNumber("0000000000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transfer(ana.getId(), "clave",
                new TransferRequest(source.getId(), "0000000000", 1_000L, null)))
                .isInstanceOf(DestinationAccountNotFoundException.class);
    }

    @Test
    void cannotTransferFromAccountOfAnotherUser() {
        stubLocks();

        assertThatThrownBy(() -> service.transfer(UUID.randomUUID(), "clave", request(1_000)))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void blockedSourceCannotSend() {
        stubLocks();
        ReflectionTestUtils.setField(source, "status", AccountStatus.BLOCKED);

        assertThatThrownBy(() -> service.transfer(ana.getId(), "clave", request(1_000)))
                .isInstanceOf(AccountBlockedException.class);
    }

    @Test
    void blockedDestinationCannotReceive() {
        stubLocks();
        ReflectionTestUtils.setField(destination, "status", AccountStatus.BLOCKED);

        assertThatThrownBy(() -> service.transfer(ana.getId(), "clave", request(1_000)))
                .isInstanceOf(DestinationAccountUnavailableException.class);
    }

    @Test
    void insufficientFundsRecordsNothing() {
        stubLocks();
        when(ledgerService.balanceOf(source.getId())).thenReturn(999L);

        assertThatThrownBy(() -> service.transfer(ana.getId(), "clave", request(1_000)))
                .isInstanceOf(InsufficientFundsException.class);
        verify(ledgerService, never()).record(any(), any(), any(), any(), anyList());
    }

    @Test
    void dailyLimitCountsFromStartOfDayInChile() {
        stubLocks();
        when(ledgerService.balanceOf(source.getId())).thenReturn(5_000_000L);
        when(ledgerService.transferredSince(source.getId(), START_OF_CHILEAN_DAY)).thenReturn(900_000L);

        assertThatThrownBy(() -> service.transfer(ana.getId(), "clave", request(100_001)))
                .isInstanceOf(DailyTransferLimitExceededException.class)
                .satisfies(e -> assertThat(((DailyTransferLimitExceededException) e).getRemaining()).isEqualTo(100_000));
        verify(ledgerService, never()).record(any(), any(), any(), any(), anyList());
    }

    @Test
    void dailyLimitAllowsExactRemainingAmount() {
        stubLocks();
        when(ledgerService.balanceOf(source.getId())).thenReturn(5_000_000L);
        when(ledgerService.transferredSince(source.getId(), START_OF_CHILEAN_DAY)).thenReturn(900_000L);
        when(userRepository.getReferenceById(ana.getId())).thenReturn(ana);
        when(ledgerService.record(any(), any(), any(), any(), anyList())).thenReturn(mock(Transaction.class));

        service.transfer(ana.getId(), "clave", request(100_000));

        verify(ledgerService).record(any(), any(), any(), any(), anyList());
    }

    // --- helpers ---------------------------------------------------------------

    private TransferRequest request(long amount) {
        return new TransferRequest(source.getId(), destination.getAccountNumber(), amount, "Test");
    }

    private void stubLocks() {
        when(accountService.findClientAccountByNumber(destination.getAccountNumber())).thenReturn(Optional.of(destination));
        when(accountService.lockInOrder(anyCollection())).thenReturn(
                Map.of(source.getId(), source, destination.getId(), destination));
    }

    private static User user(String firstName, String lastName) {
        User user = new User(firstName.toLowerCase() + "@wallet.cl", "hash", firstName, lastName, "12345678-5", null, Role.USER);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private static Account account(User owner, String number) {
        Account account = Account.openFor(owner, number, null);
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        return account;
    }
}
