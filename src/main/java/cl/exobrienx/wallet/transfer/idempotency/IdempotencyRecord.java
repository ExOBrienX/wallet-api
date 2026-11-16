package cl.exobrienx.wallet.transfer.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Respuesta guardada de una operación con {@code Idempotency-Key}. Si el cliente repite la
 * petición, se devuelve esta respuesta en vez de ejecutar la operación otra vez.
 */
@Entity
@Immutable
@Table(name = "idempotency_records")
@IdClass(IdempotencyRecord.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "key", nullable = false, length = 100)
    private String key;

    /** SHA-256 de la ruta y el cuerpo de la petición original. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false, columnDefinition = "text")
    private String responseBody;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    IdempotencyRecord(UUID userId, String key, String requestHash, int responseStatus, String responseBody) {
        this.userId = userId;
        this.key = key;
        this.requestHash = requestHash;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }

    /** Clave primaria compuesta: la misma clave puede usarla cada usuario por separado. */
    public record Key(UUID userId, String key) implements Serializable {
    }
}
