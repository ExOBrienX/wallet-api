package cl.exobrienx.wallet.auth;

import cl.exobrienx.wallet.common.util.Hashing;
import cl.exobrienx.wallet.user.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Emite tokens de acceso (JWT firmados) y genera refresh tokens (valores aleatorios opacos).
 */
@Service
public class TokenService {

    /** Claim con los roles del usuario; {@code SecurityConfig} lo convierte en autoridades. */
    public static final String ROLES_CLAIM = "roles";

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenService(JwtEncoder jwtEncoder, JwtProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String issueAccessToken(User user) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .claim("email", user.getEmail())
                .claim(ROLES_CLAIM, List.of(user.getRole().name()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long accessTokenTtlSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }

    /** Genera un refresh token: 256 bits aleatorios en Base64 URL-safe (43 caracteres). */
    public String generateRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public Instant refreshTokenExpiration() {
        return clock.instant().plus(properties.refreshTokenTtl());
    }

    /**
     * Hash SHA-256 en hexadecimal. No necesita sal ni un algoritmo lento como BCrypt:
     * el token ya es aleatorio y de alta entropía, así que no es adivinable por fuerza bruta.
     */
    public static String hash(String token) {
        return Hashing.sha256Hex(token);
    }
}
