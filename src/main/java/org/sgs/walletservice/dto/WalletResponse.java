package org.sgs.walletservice.dto;

import org.sgs.walletservice.domain.Wallet;

import java.time.Instant;

public record WalletResponse(
        Long id,
        String userId,
        long balancePaise,
        Instant createdAt
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getUserId(),
                wallet.getBalancePaise(),
                wallet.getCreatedAt()
        );
    }
}


