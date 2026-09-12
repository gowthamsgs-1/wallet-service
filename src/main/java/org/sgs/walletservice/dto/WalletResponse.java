package org.sgs.walletservice.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.sgs.walletservice.domain.Wallet;

import java.time.Instant;

public record WalletResponse(
        Long id,
        @JsonProperty("owner_id")
        @JsonAlias("ownerId")
        String ownerId,
        @JsonProperty("balance_paise")
        @JsonAlias("balancePaise")
        long balancePaise,
        @JsonProperty("created_at")
        @JsonAlias("createdAt")
        Instant createdAt
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getOwnerId(),
                wallet.getBalancePaise(),
                wallet.getCreatedAt()
        );
    }
}

