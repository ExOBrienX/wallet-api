package cl.exobrienx.wallet.common.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Reintenta una transacción completa cuando PostgreSQL la aborta por un conflicto transitorio:
 * un deadlock, una falla de serialización o un bloqueo que no se pudo obtener.
 * <p>
 * El orden fijo de bloqueos evita los deadlocks conocidos; esto es una red de seguridad para
 * los que no se previeron. Debe envolver la llamada al servicio transaccional desde afuera:
 * una transacción abortada no se puede seguir usando, así que cada intento abre una nueva.
 * <p>
 * No se reintentan los conflictos de bloqueo optimista ({@code @Version}): significan que otra
 * operación cambió el recurso, y el cliente debe decidir con los datos actualizados.
 */
@Component
public class TransactionRetry {

    private static final Logger log = LoggerFactory.getLogger(TransactionRetry.class);

    /** Reintentos después del primer intento fallido. */
    static final int MAX_RETRIES = 3;

    private final RetryTemplate retryTemplate;

    public TransactionRetry() {
        RetryPolicy policy = RetryPolicy.builder()
                .includes(PessimisticLockingFailureException.class)
                .maxRetries(MAX_RETRIES)
                // Espera creciente y algo aleatoria, para que las transacciones que chocaron no
                // vuelvan a intentarlo exactamente al mismo tiempo.
                .delay(Duration.ofMillis(20))
                .multiplier(2)
                .jitter(Duration.ofMillis(10))
                .maxDelay(Duration.ofMillis(200))
                .build();
        this.retryTemplate = new RetryTemplate(policy);
        this.retryTemplate.setRetryListener(new RetryListener() {
            @Override
            public void onRetryFailure(RetryPolicy retryPolicy, Retryable<?> retryable, Throwable throwable) {
                log.warn("Conflicto transitorio en la base de datos, se reintentará: {}", throwable.getMessage());
            }
        });
    }

    /**
     * Ejecuta la acción y la reintenta ante conflictos transitorios. Si se agotan los reintentos,
     * o si la falla es de otro tipo, propaga la última excepción tal cual.
     */
    public <T> T run(Supplier<T> action) {
        try {
            return retryTemplate.execute(action::get);
        } catch (RetryException e) {
            Throwable last = e.getLastException() != null ? e.getLastException() : e.getCause();
            if (last instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (last instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(last);
        }
    }
}
