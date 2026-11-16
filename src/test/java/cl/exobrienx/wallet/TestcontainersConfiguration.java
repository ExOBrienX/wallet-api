package cl.exobrienx.wallet;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Levanta un PostgreSQL real en Docker para los tests de integración.
 * {@code @ServiceConnection} hace que Spring configure el datasource apuntando a este contenedor.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        // Misma versión que compose.yaml, para que los tests prueben lo mismo que dev.
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    }
}
