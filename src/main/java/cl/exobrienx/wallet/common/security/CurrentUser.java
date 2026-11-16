package cl.exobrienx.wallet.common.security;

import cl.exobrienx.wallet.auth.TokenService;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

/**
 * Usuario autenticado, extraído del JWT. Los controladores lo construyen y lo pasan a los
 * servicios, así los servicios no dependen de Spring Security ni del formato del token.
 */
public record CurrentUser(UUID id, boolean admin) {

    public static CurrentUser from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(TokenService.ROLES_CLAIM);
        boolean admin = roles != null && roles.contains("ADMIN");
        return new CurrentUser(UUID.fromString(jwt.getSubject()), admin);
    }
}
