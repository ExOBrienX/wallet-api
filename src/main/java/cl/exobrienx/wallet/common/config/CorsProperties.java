package cl.exobrienx.wallet.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuración de CORS, bajo el prefijo {@code wallet.security.cors}.
 *
 * @param allowedOrigins orígenes exactos autorizados (ej. https://app.midominio.cl); vacío por defecto
 */
@ConfigurationProperties(prefix = "wallet.security.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream().map(String::trim).filter(origin -> !origin.isEmpty()).toList();
    }
}
