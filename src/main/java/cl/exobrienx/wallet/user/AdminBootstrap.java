package cl.exobrienx.wallet.user;

import cl.exobrienx.wallet.auth.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Crea el administrador inicial al arrancar, a partir de las variables de entorno
 * {@code WALLET_ADMIN_EMAIL} y {@code WALLET_ADMIN_PASSWORD}.
 * <p>
 * Se hace aquí y no en una migración para que la contraseña nunca quede en el repositorio.
 * Si el correo ya existe no se modifica nada, así que reiniciar la aplicación es seguro.
 */
@Component
@Order(1)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final Properties properties;
    private final UserRepository userRepository;
    private final AuthService authService;

    public AdminBootstrap(Properties properties, UserRepository userRepository, AuthService authService) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.authService = authService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(properties.email()) || !StringUtils.hasText(properties.password())) {
            return;
        }
        if (userRepository.existsByEmail(AuthService.normalizeEmail(properties.email()))) {
            return;
        }
        authService.createUser(new NewUser(properties.email(), properties.password(),
                "Administrador", "Wallet", null, null, Role.ADMIN));
        log.info("Administrador inicial creado: {}", properties.email());
    }

    @ConfigurationProperties(prefix = "wallet.security.bootstrap-admin")
    public record Properties(String email, String password) {
    }
}
