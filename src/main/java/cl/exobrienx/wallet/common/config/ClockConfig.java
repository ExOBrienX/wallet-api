package cl.exobrienx.wallet.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Reloj de la aplicación en UTC. Los servicios lo reciben inyectado en vez de llamar a
 * {@code Instant.now()}, para que los tests puedan fijar la hora (por ejemplo, para probar
 * que un token expiró sin tener que esperar).
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
