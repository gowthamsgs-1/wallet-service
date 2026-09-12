package org.sgs.walletservice.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.sgs.walletservice.domain.Transfer;
import org.sgs.walletservice.domain.TransferStatus;

import java.time.Instant;

public record TransferResponse(
        Long id,
        Long from,
        Long to,
        @JsonProperty("amount_paise")
        @JsonAlias("amountPaise")
        long amountPaise,
        TransferStatus status,
        @JsonProperty("failure_reason")
        @JsonAlias("failureReason")
        String failureReason,
        @JsonProperty("caller_id")
        @JsonAlias("callerId")
        String callerId,
        @JsonProperty("idempotency_key")
        @JsonAlias("idempotencyKey")
        String idempotencyKey,
        @JsonProperty("created_at")
        @JsonAlias("createdAt")
        Instant createdAt
) {
    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getFromWalletId(),
                transfer.getToWalletId(),
                transfer.getAmountPaise(),
                transfer.getStatus(),
                transfer.getFailureReason(),
                transfer.getCallerId(),
                transfer.getIdempotencyKey(),
                transfer.getCreatedAt()
        );
    }
}

