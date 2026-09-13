package org.sgs.walletservice.service;

import org.sgs.walletservice.dto.TransferRequest;
import org.sgs.walletservice.logging.DomainEvents;
import org.sgs.walletservice.metrics.WalletMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

/**
 * Shared structured-logging / metrics helper for transfer flows.
 *
 * <p>Extracted so both {@link TransferService} (orchestration + race recovery) and
 * {@link TransferExecutionService} (the transactional body) emit identical event shapes.
 */
@Component
public class TransferEventLogger {

    private static final Logger log = LoggerFactory.getLogger("org.sgs.walletservice.service.TransferService");

    private final WalletMetrics metrics;

    public TransferEventLogger(WalletMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Starts a log event pre-populated with the fields every transfer event carries.
     * The correlation id is supplied automatically from the MDC by the structured log format.
     */
    public LoggingEventBuilder event(String eventName, String callerId, TransferRequest request) {
        return log.atInfo()
                .addKeyValue("event", eventName)
                .addKeyValue("caller_id", callerId)
                .addKeyValue("from_wallet_id", request.from())
                .addKeyValue("to_wallet_id", request.to())
                .addKeyValue("amount_paise", request.amountPaise())
                .addKeyValue("idempotency_key", request.idempotencyKey());
    }

    public void declined(String callerId, TransferRequest request, String reason, String message) {
        metrics.transferDeclined(reason);
        event(DomainEvents.TRANSFER_DECLINED, callerId, request)
                .addKeyValue("reason", reason)
                .log("Transfer declined: {}", message);
    }
}

