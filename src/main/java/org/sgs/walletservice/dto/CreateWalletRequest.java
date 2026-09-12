package org.sgs.walletservice.dto;

public record CreateWalletRequest(
        String userId,
        Long initialBalancePaise
) {
}


