package cl.exobrienx.wallet.auth;

import cl.exobrienx.wallet.auth.dto.LoginRequest;
import cl.exobrienx.wallet.auth.dto.RegisterRequest;
import cl.exobrienx.wallet.auth.dto.TokenResponse;
import cl.exobrienx.wallet.common.util.Text;
import cl.exobrienx.wallet.common.validation.Rut;
import cl.exobrienx.wallet.user.EmailAlreadyRegisteredException;
import cl.exobrienx.wallet.user.NewUser;
import cl.exobrienx.wallet.user.Role;
import cl.exobrienx.wallet.user.RutAlreadyRegisteredException;
import cl.exobrienx.wallet.user.User;
import cl.exobrienx.wallet.user.UserRepository;
import cl.exobrienx.wallet.user.dto.UserResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final Clock clock;

    /**
     * Hash BCrypt de una contraseña cualquiera. Cuando el correo no existe se compara contra
     * este hash igual, para que el login tarde lo mismo exista o no el usuario y no se pueda
     * averiguar qué correos están registrados midiendo tiempos de respuesta.
     */
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       TokenService tokenService,
                       LoginAttemptLimiter loginAttemptLimiter,
                       Clock clock) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        User user = createUser(new NewUser(request.email(), request.password(), request.firstName(),
                request.lastName(), request.rut(), request.phone(), Role.USER));
        return UserResponse.from(user);
    }

    /**
     * Crea un usuario. El registro público siempre usa {@link Role#USER}; el rol ADMIN solo
     * se asigna desde la configuración inicial. Los clientes deben tener RUT.
     */
    @Transactional
    public User createUser(NewUser data) {
        String email = normalizeEmail(data.email());
        String rut = data.rut() == null ? null : Rut.normalize(data.rut())
                .orElseThrow(() -> new IllegalArgumentException("RUT inválido"));
        if (data.role() == Role.USER && rut == null) {
            throw new IllegalArgumentException("Un cliente debe tener RUT");
        }
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }
        if (rut != null && userRepository.existsByRut(rut)) {
            throw new RutAlreadyRegisteredException();
        }

        User user = new User(email, passwordEncoder.encode(data.rawPassword()),
                data.firstName().trim(), data.lastName().trim(), rut, Text.blankToNull(data.phone()), data.role());
        try {
            // saveAndFlush fuerza el INSERT aquí: si otra petición registró el mismo correo o RUT
            // entre la verificación y este punto, la restricción UNIQUE salta dentro del try.
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            String cause = String.valueOf(e.getMostSpecificCause().getMessage());
            throw cause.contains("uq_users_rut") ? new RutAlreadyRegisteredException() : new EmailAlreadyRegisteredException();
        }
    }

    /**
     * Inicia sesión. Tras varios intentos fallidos desde la misma IP para el mismo correo, se
     * rechaza con 429 durante un tiempo, aunque la contraseña sea correcta.
     *
     * @param clientIp dirección del cliente, para el límite de intentos fallidos
     */
    @Transactional
    public TokenResponse login(LoginRequest request, String clientIp) {
        loginAttemptLimiter.blockedFor(request.email(), clientIp).ifPresent(wait -> {
            throw new TooManyLoginAttemptsException(wait);
        });
        User user = userRepository.findByEmail(normalizeEmail(request.email())).orElse(null);
        String hashToCheck = user != null ? user.getPasswordHash() : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);
        if (user == null || !passwordMatches) {
            loginAttemptLimiter.recordFailure(request.email(), clientIp);
            throw new InvalidCredentialsException();
        }
        loginAttemptLimiter.recordSuccess(request.email(), clientIp);
        return issueTokens(user);
    }

    /**
     * Rota el refresh token: revoca el recibido y entrega uno nuevo junto a un token de acceso.
     * <p>
     * Si llega un token que ya estaba revocado, alguien lo está reutilizando: o el cliente
     * legítimo o un atacante tiene una copia robada. Como no se puede saber cuál, se revocan
     * todas las sesiones del usuario y debe volver a iniciar sesión.
     * <p>
     * {@code noRollbackFor} es necesario: sin él, la excepción desharía la revocación masiva.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenResponse refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(TokenService.hash(rawRefreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (token.isRevoked()) {
            int revoked = refreshTokenRepository.revokeAllForUser(token.getUser().getId(), now);
            log.warn("Reutilización de refresh token revocado del usuario {}; se revocaron {} sesiones activas",
                    token.getUser().getId(), revoked);
            throw new InvalidRefreshTokenException();
        }
        if (token.isExpired(now)) {
            throw new InvalidRefreshTokenException();
        }

        token.revoke(now);
        return issueTokens(token.getUser());
    }

    /**
     * Cierra la sesión revocando el refresh token. Es idempotente: si el token no existe,
     * ya estaba revocado o pertenece a otro usuario, no hace nada y no revela cuál fue el caso.
     * El token de acceso sigue siendo válido hasta que expira (como máximo 15 minutos).
     */
    @Transactional
    public void logout(UUID userId, String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(TokenService.hash(rawRefreshToken))
                .filter(token -> token.getUser().getId().equals(userId))
                .ifPresent(token -> token.revoke(clock.instant()));
    }

    private TokenResponse issueTokens(User user) {
        String refreshToken = tokenService.generateRefreshToken();
        refreshTokenRepository.save(new RefreshToken(
                user, TokenService.hash(refreshToken), tokenService.refreshTokenExpiration()));
        return TokenResponse.bearer(
                tokenService.issueAccessToken(user), tokenService.accessTokenTtlSeconds(), refreshToken);
    }

    /** Los correos se guardan en minúsculas y sin espacios, para que "Ana@x.cl" y "ana@x.cl" sean el mismo. */
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

}
