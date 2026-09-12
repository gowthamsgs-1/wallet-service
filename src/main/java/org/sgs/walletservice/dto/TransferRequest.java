package org.sgs.walletservice.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransferRequest(
        @NotNull(message = "'from' wallet id is required")
        Long from,

        @NotNull(message = "'to' wallet id is required")
        Long to,

        @NotNull(message = "'amount_paise' is required")
        @Positive(message = "'amount_paise' must be positive")
        @JsonProperty("amount_paise")
        @JsonAlias({"amountPaise", "amount_paise"})
        Long amountPaise,

        @NotBlank(message = "'idempotency_key' is required")
        @JsonProperty("idempotency_key")
        @JsonAlias({"idempotencyKey", "idempotency_key"})
        String idempotencyKey
) {
}

