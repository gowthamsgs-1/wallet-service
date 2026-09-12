package org.sgs.walletservice.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Domain counters for the wallet service.
 *
 * <p>Counters are resolved once at construction rather than on every increment, so the hot
 * path does not pay for meter lookup. All of them are exposed through {@code /metrics}
 * alongside the automatic HTTP metrics.
 */
@Component
public class WalletMetrics {

    private final Counter transfersCreated;
    private final Counter transfersDeclinedInsufficientFunds;
    private final Counter transfersIdempotentReplays;
    private final MeterRegistry registry;

    public WalletMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.transfersCreated = Counter.builder("wallet.transfers.created")
                .description("Transfers that completed successfully")
                .baseUnit("transfers")
                .register(registry);

        this.transfersDeclinedInsufficientFunds = Counter.builder("wallet.transfers.declined")
                .description("Transfers refused by the service")
                .tag("reason", "insufficient_funds")
                .baseUnit("transfers")
                .register(registry);

        this.transfersIdempotentReplays = Counter.builder("wallet.transfers.idempotent_replays")
                .description("Repeat requests for an already-processed idempotency key")
                .baseUnit("transfers")
                .register(registry);
    }

    public void transferCreated() {
        this.transfersCreated.increment();
    }

    public void transferDeclinedInsufficientFunds() {
        this.transfersDeclinedInsufficientFunds.increment();
    }

    public void transferIdempotentReplay() {
        this.transfersIdempotentReplays.increment();
    }

    /**
     * Declines for reasons other than funds (validation, ownership, idempotency conflicts).
     * Tagged per reason so one series covers every rejection cause.
     */
    public void transferDeclined(String reason) {
        Counter.builder("wallet.transfers.declined")
                .description("Transfers refused by the service")
                .tag("reason", reason)
                .baseUnit("transfers")
                .register(this.registry)
                .increment();
    }
}

