package cl.exobrienx.wallet.auth;

import cl.exobrienx.wallet.auth.dto.LoginRequest;
import cl.exobrienx.wallet.auth.dto.RegisterRequest;
import cl.exobrienx.wallet.auth.dto.TokenResponse;
import cl.exobrienx.wallet.user.EmailAlreadyRegisteredException;
import cl.exobrienx.wallet.user.NewUser;
import cl.exobrienx.wallet.user.Role;
import cl.exobrienx.wallet.user.RutAlreadyRegisteredException;
import cl.exobrienx.wallet.user.User;
import cl.exobrienx.wallet.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");
    private static final String DUMMY_HASH = "dummy-hash";
    private static final String IP = "203.0.113.10";

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TokenService tokenService;
    @Mock
    private LoginAttemptLimiter loginAttemptLimiter;

    private AuthService authService;
    private User user;

    @BeforeEach
    void setUp() {
        // El constructor genera el hash ficticio usado cuando el correo no existe.
        when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        authService = new AuthService(userRepository, refreshTokenRepository, passwordEncoder, tokenService,
                loginAttemptLimiter, clock);

        user = new User("ana@wallet.cl", "ana-hash", "Ana", "Pérez", "12345678-5", null, Role.USER);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
    }

    // --- registro --------------------------------------------------------------

    @Test
    void registerNormalizesEmailAndAlwaysAssignsUserRole() {
        when(userRepository.existsByEmail("ana@wallet.cl")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.register(new RegisterRequest("  Ana@Wallet.CL ", "secreta123", " Ana ", "Pérez", "12.345.678-5", ""));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ana@wallet.cl");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.USER);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo(DUMMY_HASH);
        assertThat(saved.getValue().getRut()).isEqualTo("12345678-5");
        assertThat(saved.getValue().getFirstName()).isEqualTo("Ana");
        assertThat(saved.getValue().getPhone()).isNull();
    }

    @Test
    void registerRejectsExistingRut() {
        when(userRepository.existsByEmail("ana@wallet.cl")).thenReturn(false);
        when(userRepository.existsByRut("12345678-5")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("ana@wallet.cl", "secreta123", "Ana", "Pérez", "12345678-5", null)))
                .isInstanceOf(RutAlreadyRegisteredException.class);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void clientWithoutRutCannotBeCreated() {
        assertThatThrownBy(() -> authService.createUser(
                new NewUser("ana@wallet.cl", "secreta123", "Ana", "Pérez", null, null, Role.USER)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerRejectsExistingEmail() {
        when(userRepository.existsByEmail("ana@wallet.cl")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("ana@wallet.cl", "secreta123", "Ana", "Pérez", "12345678-5", null)))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerTranslatesConcurrentDuplicateIntoConflict() {
        // Otra petición registró el mismo correo entre la verificación y el INSERT.
        when(userRepository.existsByEmail("ana@wallet.cl")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("uq_users_email"));

        assertThatThrownBy(() -> authService.register(new RegisterRequest("ana@wallet.cl", "secreta123", "Ana", "Pérez", "12345678-5", null)))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    // --- login -----------------------------------------------------------------

    @Test
    void loginReturnsTokensAndStoresOnlyRefreshTokenHash() {
        when(userRepository.findByEmail("ana@wallet.cl")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secreta123", "ana-hash")).thenReturn(true);
        stubTokenIssuing("nuevo-refresh");

        TokenResponse response = authService.login(new LoginRequest("ana@wallet.cl", "secreta123"), IP);

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("nuevo-refresh");
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        assertThat(saved.getValue().getTokenHash())
                .isEqualTo(TokenService.hash("nuevo-refresh"))
                .isNotEqualTo("nuevo-refresh");
    }

    @Test
    void loginWithWrongPasswordFails() {
        when(userRepository.findByEmail("ana@wallet.cl")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("incorrecta", "ana-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("ana@wallet.cl", "incorrecta"), IP))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(refreshTokenRepository, never()).save(any());
        verify(loginAttemptLimiter).recordFailure("ana@wallet.cl", IP);
    }

    @Test
    void blockedLoginIsRejectedEvenWithCorrectPassword() {
        when(loginAttemptLimiter.blockedFor("ana@wallet.cl", IP)).thenReturn(Optional.of(Duration.ofMinutes(10)));

        assertThatThrownBy(() -> authService.login(new LoginRequest("ana@wallet.cl", "secreta123"), IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);
        // Ni siquiera se consulta la base ni se compara la contraseña.
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void loginWithUnknownEmailStillChecksAPasswordHash() {
        when(userRepository.findByEmail("nadie@wallet.cl")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nadie@wallet.cl", "secreta123"), IP))
                .isInstanceOf(InvalidCredentialsException.class);
        // Se compara contra el hash ficticio para que la respuesta tarde lo mismo.
        verify(passwordEncoder).matches("secreta123", DUMMY_HASH);
    }

    // --- refresh ---------------------------------------------------------------

    @Test
    void refreshRotatesToken() {
        RefreshToken current = new RefreshToken(user, TokenService.hash("actual"), NOW.plus(Duration.ofDays(1)));
        when(refreshTokenRepository.findByTokenHashForUpdate(TokenService.hash("actual")))
                .thenReturn(Optional.of(current));
        stubTokenIssuing("siguiente");

        TokenResponse response = authService.refresh("actual");

        assertThat(current.isRevoked()).isTrue();
        assertThat(current.getRevokedAt()).isEqualTo(NOW);
        assertThat(response.refreshToken()).isEqualTo("siguiente");
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void refreshWithUnknownTokenFails() {
        when(refreshTokenRepository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("inventado"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refreshWithExpiredTokenFails() {
        RefreshToken expired = new RefreshToken(user, TokenService.hash("viejo"), NOW);
        when(refreshTokenRepository.findByTokenHashForUpdate(TokenService.hash("viejo")))
                .thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.refresh("viejo"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void reusingRevokedTokenRevokesAllUserSessions() {
        RefreshToken revoked = new RefreshToken(user, TokenService.hash("robado"), NOW.plus(Duration.ofDays(1)));
        revoked.revoke(NOW.minus(Duration.ofMinutes(5)));
        when(refreshTokenRepository.findByTokenHashForUpdate(TokenService.hash("robado")))
                .thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> authService.refresh("robado"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenRepository).revokeAllForUser(user.getId(), NOW);
        verify(refreshTokenRepository, never()).save(any());
    }

    // --- logout ----------------------------------------------------------------

    @Test
    void logoutRevokesOwnToken() {
        RefreshToken token = new RefreshToken(user, TokenService.hash("mio"), NOW.plus(Duration.ofDays(1)));
        when(refreshTokenRepository.findByTokenHash(TokenService.hash("mio"))).thenReturn(Optional.of(token));

        authService.logout(user.getId(), "mio");

        assertThat(token.isRevoked()).isTrue();
    }

    @Test
    void logoutIgnoresTokenOfAnotherUser() {
        RefreshToken token = new RefreshToken(user, TokenService.hash("ajeno"), NOW.plus(Duration.ofDays(1)));
        when(refreshTokenRepository.findByTokenHash(TokenService.hash("ajeno"))).thenReturn(Optional.of(token));

        authService.logout(UUID.randomUUID(), "ajeno");

        assertThat(token.isRevoked()).isFalse();
    }

    private void stubTokenIssuing(String refreshToken) {
        when(tokenService.generateRefreshToken()).thenReturn(refreshToken);
        when(tokenService.refreshTokenExpiration()).thenReturn(NOW.plus(Duration.ofDays(7)));
        when(tokenService.issueAccessToken(eq(user))).thenReturn("access-token");
        when(tokenService.accessTokenTtlSeconds()).thenReturn(900L);
    }
}
