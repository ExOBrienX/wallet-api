package cl.exobrienx.wallet.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LedgerHealthIndicatorTest {

    @Test
    void databaseFailureIsReportedAsDownInsteadOfThrowing() {
        // Con la base caída, el health debe responder DOWN (503), no propagar la excepción (500).
        JdbcClient jdbc = mock(JdbcClient.class);
        when(jdbc.sql(anyString())).thenThrow(new CannotGetJdbcConnectionException("Connection refused"));

        Health health = new LedgerHealthIndicator(jdbc).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }
}
