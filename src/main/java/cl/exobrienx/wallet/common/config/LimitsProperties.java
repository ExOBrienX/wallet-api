package cl.exobrienx.wallet.common.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.ZoneId;

/**
 * Límites de negocio, bajo el prefijo {@code wallet.limits}.
 *
 * @param dailyTransfer      monto máximo (CLP) que una cuenta puede transferir por día
 * @param timezone           zona horaria que define el corte del "día" para el límite diario
 * @param maxAccountsPerUser cantidad máxima de cuentas que puede abrir un usuario
 */
@Validated
@ConfigurationProperties(prefix = "wallet.limits")
public record LimitsProperties(
        @Min(1) long dailyTransfer,
        @NotNull ZoneId timezone,
        @Min(1) int maxAccountsPerUser) {
}
