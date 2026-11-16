package cl.exobrienx.wallet.common.config;

import cl.exobrienx.wallet.auth.JwtProperties;
import cl.exobrienx.wallet.auth.TokenService;
import cl.exobrienx.wallet.transfer.idempotency.IdempotentResponse;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seguridad de la API: autenticación sin estado con JWT (Spring Security OAuth2 Resource Server).
 * <p>
 * Los tokens se firman con HMAC-SHA256 y una clave simétrica. Es suficiente porque esta misma
 * API emite y verifica los tokens; si otros servicios tuvieran que verificarlos, convendría
 * RS256 (clave privada para firmar, pública para verificar).
 */
@Configuration
public class SecurityConfig {

    private final HandlerExceptionResolver exceptionResolver;

    /**
     * Los errores de autenticación ocurren en los filtros, antes de llegar a los controladores,
     * así que el {@code @RestControllerAdvice} no los ve. Se delegan a este resolver para que
     * también respondan con {@code ProblemDetail} desde el mismo manejador global.
     */
    public SecurityConfig(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // API REST sin estado: no hay sesiones ni cookies, así que CSRF no aplica.
                .csrf(csrf -> csrf.disable())
                // CORS según la lista de orígenes configurada (vacía por defecto: ninguno).
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Además de los headers que Spring Security agrega por defecto (nosniff,
                // X-Frame-Options DENY, Cache-Control no-store), no enviar la URL de origen.
                .headers(headers -> headers.referrerPolicy(referrer -> referrer
                        .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/register", "/api/auth/login", "/api/auth/refresh").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Un administrador puede consultar cualquier cuenta, pero no operar como cliente.
                        .requestMatchers(HttpMethod.GET, "/api/accounts/*", "/api/accounts/*/transactions")
                        .hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/accounts", "/api/accounts/**",
                                "/api/deposits", "/api/withdrawals", "/api/transfers").hasRole("USER")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()));
        return http.build();
    }

    /**
     * Orígenes web autorizados a llamar a la API desde un navegador (CORS). Por defecto la lista
     * está vacía y ningún sitio puede hacerlo: la API no tiene frontend propio. Si se agrega uno,
     * su dominio se habilita con la variable CORS_ALLOWED_ORIGINS, sin cambiar el código.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "Idempotency-Key"));
        // Headers de respuesta que el código del navegador puede leer.
        cors.setExposedHeaders(List.of(HttpHeaders.LOCATION, HttpHeaders.RETRY_AFTER, IdempotentResponse.REPLAYED_HEADER));
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    /**
     * El firewall de Spring Security rechaza URLs sospechosas (por ejemplo, con "..;/", "%2e" o
     * "%25") antes de llegar a la aplicación. Por defecto lo hace con un 400 sin cuerpo; aquí se
     * delega al manejador global para responder también con ProblemDetail y su motivo.
     */
    @Bean
    RequestRejectedHandler requestRejectedHandler() {
        return (request, response, ex) -> exceptionResolver.resolveException(request, response, null, ex);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Valida expiración (con el margen por defecto) y que el emisor sea esta API.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer())));
        return decoder;
    }

    /** Convierte el claim "roles" del JWT en autoridades ROLE_USER / ROLE_ADMIN. */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, ex) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            exceptionResolver.resolveException(request, response, null, ex);
        };
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> exceptionResolver.resolveException(request, response, null, ex);
    }

    private static SecretKey signingKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
