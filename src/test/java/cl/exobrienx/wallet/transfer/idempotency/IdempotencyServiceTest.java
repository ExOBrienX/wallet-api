package cl.exobrienx.wallet.transfer.idempotency;

import cl.exobrienx.wallet.common.util.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final String OPERATION = "POST /api/deposits";

    @Mock
    private IdempotencyRecordRepository repository;

    private IdempotencyService service;
    private final AtomicInteger executions = new AtomicInteger();
    private final Supplier<Payload> action = () -> new Payload("resultado-" + executions.incrementAndGet());

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository, JsonMapper.builder().build());
    }

    @Test
    void newKeyExecutesActionAndStoresResponse() {
        when(repository.findByUserIdAndKey(USER, "k")).thenReturn(Optional.empty());

        IdempotentResponse response = service.execute(USER, "k", OPERATION, new Payload("a"), HttpStatus.CREATED, action);

        assertThat(executions).hasValue(1);
        assertThat(response.replayed()).isFalse();
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body()).isEqualTo("{\"value\":\"resultado-1\"}");
        ArgumentCaptor<IdempotencyRecord> saved = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getResponseBody()).isEqualTo(response.body());
        verify(repository).acquireTransactionLock(USER + ":k");
    }

    @Test
    void sameKeyAndSameRequestReturnsStoredResponseWithoutExecuting() {
        String hash = Hashing.sha256Hex(OPERATION + "\n{\"value\":\"a\"}");
        when(repository.findByUserIdAndKey(USER, "k"))
                .thenReturn(Optional.of(new IdempotencyRecord(USER, "k", hash, 201, "{\"guardado\":true}")));

        IdempotentResponse response = service.execute(USER, "k", OPERATION, new Payload("a"), HttpStatus.CREATED, action);

        assertThat(executions).hasValue(0);
        assertThat(response.replayed()).isTrue();
        assertThat(response.body()).isEqualTo("{\"guardado\":true}");
        verify(repository, never()).save(any());
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        String hash = Hashing.sha256Hex(OPERATION + "\n{\"value\":\"a\"}");
        when(repository.findByUserIdAndKey(USER, "k"))
                .thenReturn(Optional.of(new IdempotencyRecord(USER, "k", hash, 201, "{}")));

        assertThatThrownBy(() -> service.execute(USER, "k", OPERATION, new Payload("b"), HttpStatus.CREATED, action))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(executions).hasValue(0);
    }

    @Test
    void sameKeyOnAnotherEndpointIsRejected() {
        String hash = Hashing.sha256Hex(OPERATION + "\n{\"value\":\"a\"}");
        when(repository.findByUserIdAndKey(USER, "k"))
                .thenReturn(Optional.of(new IdempotencyRecord(USER, "k", hash, 201, "{}")));

        assertThatThrownBy(() -> service.execute(USER, "k", "POST /api/withdrawals", new Payload("a"),
                HttpStatus.CREATED, action))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void failedActionStoresNothing() {
        when(repository.findByUserIdAndKey(USER, "k")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(USER, "k", OPERATION, new Payload("a"), HttpStatus.CREATED,
                () -> {
                    throw new IllegalStateException("falló");
                }))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
    }

    record Payload(String value) {
    }
}
