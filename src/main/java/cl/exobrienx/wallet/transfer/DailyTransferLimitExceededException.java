package cl.exobrienx.wallet.transfer;

import cl.exobrienx.wallet.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class DailyTransferLimitExceededException extends DomainException {

    private final long remaining;

    public DailyTransferLimitExceededException(long limit, long remaining) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "Límite diario excedido",
                "La transferencia supera el límite diario de $" + limit + ". Hoy puedes transferir hasta $"
                        + remaining + " más desde esta cuenta.");
        this.remaining = remaining;
    }

    public long getRemaining() {
        return remaining;
    }
}
