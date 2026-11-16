package cl.exobrienx.wallet.ledger;

import cl.exobrienx.wallet.account.Account;

/**
 * Un asiento a registrar: qué cuenta, en qué sentido y por cuánto.
 */
public record Posting(Account account, EntryDirection direction, long amount) {

    public static Posting debit(Account account, long amount) {
        return new Posting(account, EntryDirection.DEBIT, amount);
    }

    public static Posting credit(Account account, long amount) {
        return new Posting(account, EntryDirection.CREDIT, amount);
    }
}
