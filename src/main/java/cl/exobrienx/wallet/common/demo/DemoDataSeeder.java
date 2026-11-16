package cl.exobrienx.wallet.common.demo;

import cl.exobrienx.wallet.account.AccountService;
import cl.exobrienx.wallet.account.dto.AccountResponse;
import cl.exobrienx.wallet.auth.AuthService;
import cl.exobrienx.wallet.ledger.TransactionRepository;
import cl.exobrienx.wallet.transfer.CashMovementService;
import cl.exobrienx.wallet.transfer.TransferService;
import cl.exobrienx.wallet.transfer.dto.CashMovementRequest;
import cl.exobrienx.wallet.transfer.dto.TransferRequest;
import cl.exobrienx.wallet.user.NewUser;
import cl.exobrienx.wallet.user.Role;
import cl.exobrienx.wallet.user.User;
import cl.exobrienx.wallet.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Carga datos de demostración en el perfil dev: clientes ficticios con cuentas, depósitos,
 * retiros y transferencias entre ellos.
 * <p>
 * Los datos se crean a través de los mismos servicios que usa la API, no con SQL directo:
 * las contraseñas pasan por BCrypt y cada movimiento por el libro contable, así que la demo
 * nunca puede contener algo que la API no permitiría. Si los datos ya existen, no hace nada.
 * Nunca se ejecuta en tests ni en producción ({@code @Profile("dev")}).
 */
@Component
@Profile("dev")
@Order(2) // después del administrador inicial
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final Properties properties;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final AccountService accountService;
    private final CashMovementService cashMovementService;
    private final TransferService transferService;
    private final TransactionRepository transactionRepository;
    private final TransactionTemplate transactionTemplate;

    public DemoDataSeeder(Properties properties, UserRepository userRepository, AuthService authService,
                          AccountService accountService, CashMovementService cashMovementService,
                          TransferService transferService, TransactionRepository transactionRepository,
                          TransactionTemplate transactionTemplate) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.authService = authService;
        this.accountService = accountService;
        this.cashMovementService = cashMovementService;
        this.transferService = transferService;
        this.transactionRepository = transactionRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            return;
        }
        if (!userRepository.existsByEmail(CLIENTS.getFirst().email())) {
            transactionTemplate.executeWithoutResult(status -> CLIENTS.forEach(this::seed));
            log.info("Datos de demostración cargados: {} clientes. Contraseña común: la de wallet.demo.password",
                    CLIENTS.size());
        }
        transactionTemplate.executeWithoutResult(status -> seedTransfers());
    }

    /** Cada transferencia se identifica por su clave de idempotencia y se crea solo si falta. */
    private void seedTransfers() {
        int created = 0;
        for (int i = 0; i < TRANSFERS.size(); i++) {
            DemoTransfer demo = TRANSFERS.get(i);
            String key = "demo-transfer-" + (i + 1);
            User sender = userRepository.findByEmail(demo.fromEmail()).orElseThrow();
            if (transactionRepository.existsByInitiatedByIdAndIdempotencyKey(sender.getId(), key)) {
                continue;
            }
            UUID sourceId = accountOf(sender.getId(), demo.fromAlias()).id();
            User recipient = userRepository.findByEmail(demo.toEmail()).orElseThrow();
            String destinationNumber = accountOf(recipient.getId(), demo.toAlias()).accountNumber();
            transferService.transfer(sender.getId(), key,
                    new TransferRequest(sourceId, destinationNumber, demo.amount(), demo.description()));
            created++;
        }
        if (created > 0) {
            log.info("Transferencias de demostración cargadas: {}", created);
        }
    }

    private AccountResponse accountOf(UUID userId, String alias) {
        return accountService.listOwn(userId).stream()
                .filter(account -> alias.equals(account.alias()))
                .findFirst()
                .orElseThrow();
    }

    private void seed(DemoClient client) {
        User user = authService.createUser(new NewUser(client.email(), properties.password(),
                client.firstName(), client.lastName(), client.rut(), client.phone(), Role.USER));

        for (DemoAccount demoAccount : client.accounts()) {
            AccountResponse account = accountService.open(user.getId(), demoAccount.alias());
            int sequence = 0;
            for (DemoMovement movement : demoAccount.movements()) {
                String key = "demo-" + account.accountNumber() + "-" + (++sequence);
                CashMovementRequest request = new CashMovementRequest(account.id(), Math.abs(movement.amount()),
                        movement.description());
                if (movement.amount() > 0) {
                    cashMovementService.deposit(user.getId(), key, request);
                } else {
                    cashMovementService.withdraw(user.getId(), key, request);
                }
            }
        }
    }

    // --- datos ficticios (los RUT son válidos pero inventados) ----------------------

    /** @param amount positivo para depósito, negativo para retiro */
    private record DemoMovement(long amount, String description) {
    }

    private record DemoAccount(String alias, List<DemoMovement> movements) {
    }

    private record DemoClient(String email, String firstName, String lastName, String rut, String phone,
                              List<DemoAccount> accounts) {
    }

    private record DemoTransfer(String fromEmail, String fromAlias, String toEmail, String toAlias,
                                long amount, String description) {
    }

    private static final List<DemoTransfer> TRANSFERS = List.of(
            new DemoTransfer("camila.rojas@demo.cl", "Cuenta principal",
                    "matias.gonzalez@demo.cl", "Gastos", 45_000, "Mitad del asado"),
            new DemoTransfer("benjamin.soto@demo.cl", "Emprendimiento",
                    "valentina.munoz@demo.cl", "Universidad", 120_000, "Diseño de logo"),
            new DemoTransfer("matias.gonzalez@demo.cl", "Gastos",
                    "camila.rojas@demo.cl", "Cuenta principal", 15_990, "Entradas al cine"),
            new DemoTransfer("camila.rojas@demo.cl", "Cuenta principal",
                    "camila.rojas@demo.cl", "Ahorro vacaciones", 200_000, "Traspaso a ahorro"));

    private static final List<DemoClient> CLIENTS = List.of(
            new DemoClient("camila.rojas@demo.cl", "Camila", "Rojas Fuentes", "18452391-4", "+56987654321", List.of(
                    new DemoAccount("Cuenta principal", List.of(
                            new DemoMovement(1_250_000, "Sueldo octubre"),
                            new DemoMovement(-80_000, "Retiro cajero automático"),
                            new DemoMovement(-450_000, "Pago arriendo"),
                            new DemoMovement(1_250_000, "Sueldo noviembre"))),
                    new DemoAccount("Ahorro vacaciones", List.of(
                            new DemoMovement(300_000, "Ahorro mensual"),
                            new DemoMovement(300_000, "Ahorro mensual"))))),
            new DemoClient("matias.gonzalez@demo.cl", "Matías", "González Pérez", "16903274-2", "+56912345678", List.of(
                    new DemoAccount("Gastos", List.of(
                            new DemoMovement(890_000, "Pago honorarios proyecto"),
                            new DemoMovement(-35_990, "Retiro para feria"),
                            new DemoMovement(-120_000, "Retiro efectivo"))))),
            new DemoClient("valentina.munoz@demo.cl", "Valentina", "Muñoz Contreras", "20117563-1", null, List.of(
                    new DemoAccount("Universidad", List.of(
                            new DemoMovement(250_000, "Beca de alimentación"),
                            new DemoMovement(150_000, "Aporte familiar"),
                            new DemoMovement(-42_500, "Retiro materiales"))))),
            new DemoClient("benjamin.soto@demo.cl", "Benjamín", "Soto Araya", "19588042-5", "+56955512345", List.of(
                    new DemoAccount("Emprendimiento", List.of(
                            new DemoMovement(2_340_000, "Ventas tienda online"),
                            new DemoMovement(-610_000, "Pago proveedores"),
                            new DemoMovement(1_120_500, "Ventas tienda online"))),
                    new DemoAccount("Personal", List.of(
                            new DemoMovement(500_000, "Retiro de utilidades"))))));

    @ConfigurationProperties(prefix = "wallet.demo")
    public record Properties(boolean enabled, String password) {
    }
}
