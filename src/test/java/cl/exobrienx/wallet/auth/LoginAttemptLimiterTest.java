package cl.exobrienx.wallet.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptLimiterTest {

    private static final String EMAIL = "ana@wallet.cl";
    private static final String IP = "203.0.113.10";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-05-01T12:00:00Z"));
    private LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new LoginAttemptLimiter(new LoginAttemptLimiter.Properties(5, Duration.ofMinutes(15)), clock);
    }

    @Test
    void blocksAfterMaxFailedAttempts() {
        failTimes(4);
        assertThat(limiter.blockedFor(EMAIL, IP)).isEmpty();

        failTimes(1);
        assertThat(limiter.blockedFor(EMAIL, IP)).contains(Duration.ofMinutes(15));
    }

    @Test
    void blockExpiresWhenWindowEnds() {
        failTimes(5);

        clock.advance(Duration.ofMinutes(14));
        assertThat(limiter.blockedFor(EMAIL, IP)).contains(Duration.ofMinutes(1));

        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.blockedFor(EMAIL, IP)).isEmpty();
    }

    @Test
    void successfulLoginResetsCounter() {
        failTimes(4);
        limiter.recordSuccess(EMAIL, IP);
        failTimes(4);

        assertThat(limiter.blockedFor(EMAIL, IP)).isEmpty();
    }

    @Test
    void sameEmailFromAnotherIpIsNotBlocked() {
        // Así un atacante no puede bloquear la cuenta de otra persona desde su propia IP.
        failTimes(5);

        assertThat(limiter.blockedFor(EMAIL, "198.51.100.7")).isEmpty();
    }

    @Test
    void emailIsNormalizedBeforeCounting() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(i % 2 == 0 ? " Ana@Wallet.CL " : EMAIL, IP);
        }

        assertThat(limiter.blockedFor(EMAIL, IP)).isPresent();
    }

    private void failTimes(int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(EMAIL, IP);
        }
    }

    /** Reloj que el test puede adelantar, para probar la expiración sin esperar. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
