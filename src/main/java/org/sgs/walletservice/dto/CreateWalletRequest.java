package org.sgs.walletservice.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateWalletRequest(
        @PositiveOrZero(message = "Initial balance must be zero or positive")
        @JsonProperty("initial_balance_paise")
        @JsonAlias({"initialBalancePaise", "balance_paise", "balancePaise", "amount_paise", "amountPaise"})
        Long initialBalancePaise,

        /**
         * Optional explicit owner. Only honoured when the request is not authenticated
         * (the bearer token always wins when present).
         */
        @JsonProperty("user_id")
        @JsonAlias({"userId", "owner_id", "ownerId"})
        String userId
) {
}
