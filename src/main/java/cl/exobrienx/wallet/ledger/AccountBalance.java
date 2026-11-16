package cl.exobrienx.wallet.ledger;

import java.util.UUID;

public record AccountBalance(UUID accountId, long balance) {
}
