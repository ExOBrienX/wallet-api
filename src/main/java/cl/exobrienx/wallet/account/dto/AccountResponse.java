package cl.exobrienx.wallet.account.dto;

import cl.exobrienx.wallet.account.Account;
import cl.exobrienx.wallet.account.AccountStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * @param balance saldo actual en pesos, calculado desde el libro contable
 */
public record AccountResponse(
        UUID id,
        String accountNumber,
        String alias,
        String currency,
        AccountStatus status,
        long balance,
        Instant createdAt) {

    public static AccountResponse from(Account account, long balance) {
        return new AccountResponse(account.getId(), account.getAccountNumber(), account.getAlias(),
                account.getCurrency(), account.getStatus(), balance, account.getCreatedAt());
    }
}
