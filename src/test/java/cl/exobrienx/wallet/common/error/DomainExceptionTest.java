package cl.exobrienx.wallet.common.error;

import cl.exobrienx.wallet.account.AccountNotFoundException;
import cl.exobrienx.wallet.auth.InvalidCredentialsException;
import cl.exobrienx.wallet.transfer.DailyTransferLimitExceededException;
import cl.exobrienx.wallet.transfer.InsufficientFundsException;
import cl.exobrienx.wallet.transfer.idempotency.IdempotencyKeyReusedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DomainExceptionTest {

    @Test
    void codeIsDerivedFromClassName() {
        assertThat(new InsufficientFundsException().getCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(new AccountNotFoundException().getCode()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(new InvalidCredentialsException().getCode()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(new IdempotencyKeyReusedException().getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(new DailyTransferLimitExceededException(1_000_000, 0).getCode())
                .isEqualTo("DAILY_TRANSFER_LIMIT_EXCEEDED");
    }

    @Test
    void codeHandlesDigitsAndConsecutiveCapitals() {
        assertThat(DomainException.codeOf(Utf8ErrorException.class)).isEqualTo("UTF8_ERROR");
    }

    private static final class Utf8ErrorException extends DomainException {
        Utf8ErrorException() {
            super(org.springframework.http.HttpStatus.BAD_REQUEST, "t", "d");
        }
    }
}
