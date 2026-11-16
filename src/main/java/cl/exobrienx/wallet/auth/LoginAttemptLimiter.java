package cl.exobrienx.wallet.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limita los intentos fallidos de inicio de sesión para frenar ataques de fuerza bruta.
 * <p>
 * Los fallos se cuentan por combinación de correo e IP. Contar solo por correo permitiría a un
 * atacante bloquear a propósito la cuenta de otra persona; contar solo por IP no protegería a
 * una cuenta atacada desde muchas direcciones. Tras {@code maxAttempts} fallos dentro de la
 * ventana, ese correo queda bloqueado desde esa IP hasta que la ventana expire, aunque luego se
 * ingrese la contraseña correcta. Un login exitoso reinicia el contador.
 * <p>
 * Los contadores viven en memoria: sirven para una sola instancia de la API. Con varias
 * instancias habría que compartirlos, por ejemplo en Redis.
 */
@Component
public class LoginAttemptLimiter {

    /** Sobre este tamaño se eliminan las entradas vencidas, para acotar la memoria usada. */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Properties properties;
    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public LoginAttemptLimiter(Properties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Tiempo que falta para poder intentar de nuevo, si la combinación está bloqueada. */
    public Optional<Duration> blockedFor(String email, String clientIp) {
        Attempts current = attempts.get(key(email, clientIp));
        Instant now = clock.instant();
        if (current == null || current.isExpired(now, properties.window())
                || current.failures() < properties.maxAttempts()) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, current.windowStart().plus(properties.window())));
    }

    public void recordFailure(String email, String clientIp) {
        Instant now = clock.instant();
        attempts.compute(key(email, clientIp), (key, current) ->
                current == null || current.isExpired(now, properties.window())
                        ? new Attempts(1, now)
                        : new Attempts(current.failures() + 1, current.windowStart()));
        if (attempts.size() > CLEANUP_THRESHOLD) {
            attempts.values().removeIf(a -> a.isExpired(now, properties.window()));
        }
    }

    public void recordSuccess(String email, String clientIp) {
        attempts.remove(key(email, clientIp));
    }

    private static String key(String email, String clientIp) {
        return AuthService.normalizeEmail(email) + "|" + clientIp;
    }

    private record Attempts(int failures, Instant windowStart) {
        boolean isExpired(Instant now, Duration window) {
            return !now.isBefore(windowStart.plus(window));
        }
    }

    /**
     * @param maxAttempts intentos fallidos permitidos dentro de la ventana
     * @param window      duración de la ventana, que también es el tiempo de bloqueo
     */
    @ConfigurationProperties(prefix = "wallet.security.login")
    public record Properties(int maxAttempts, Duration window) {
    }
}
