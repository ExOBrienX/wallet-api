package cl.exobrienx.wallet.common.persistence;

import cl.exobrienx.wallet.transfer.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionRetryTest {

    private final TransactionRetry retry = new TransactionRetry();
    private final AtomicInteger attempts = new AtomicInteger();

    @Test
    void retriesDeadlockUntilItSucceeds() {
        String result = retry.run(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new CannotAcquireLockException("deadlock detected");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void retriesSerializationFailures() {
        retry.run(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new CannotSerializeTransactionException("could not serialize access");
            }
            return null;
        });

        assertThat(attempts).hasValue(2);
    }

    @Test
    void givesUpAfterMaxRetriesAndPropagatesOriginalException() {
        assertThatThrownBy(() -> retry.run(() -> {
            attempts.incrementAndGet();
            throw new CannotAcquireLockException("deadlock detected");
        })).isInstanceOf(CannotAcquireLockException.class);

        assertThat(attempts).hasValue(1 + TransactionRetry.MAX_RETRIES);
    }

    @Test
    void businessErrorsAreNotRetried() {
        assertThatThrownBy(() -> retry.run(() -> {
            attempts.incrementAndGet();
            throw new InsufficientFundsException();
        })).isInstanceOf(InsufficientFundsException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void optimisticLockConflictsAreNotRetried() {
        // Significan que otra operación cambió el recurso: lo decide el cliente, no un reintento.
        assertThatThrownBy(() -> retry.run(() -> {
            attempts.incrementAndGet();
            throw new OptimisticLockingFailureException("version changed");
        })).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(attempts).hasValue(1);
    }
}
