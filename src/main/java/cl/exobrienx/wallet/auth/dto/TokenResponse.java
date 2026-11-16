package cl.exobrienx.wallet.auth.dto;

/**
 * Tokens entregados en el login y en cada refresh.
 *
 * @param expiresIn segundos de vigencia del token de acceso
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String refreshToken) {

    public static TokenResponse bearer(String accessToken, long expiresIn, String refreshToken) {
        return new TokenResponse(accessToken, "Bearer", expiresIn, refreshToken);
    }
}
