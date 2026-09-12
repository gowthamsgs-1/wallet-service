package org.sgs.walletservice.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
public class Wallet {

    private Long id;
    private String userId;
    private long balancePaise;
    private Instant createdAt = Instant.now();

    public Wallet(Long id, String userId, long balancePaise) {
        this.id = id;
        this.userId = userId;
        this.balancePaise = balancePaise;
        this.createdAt = Instant.now();
    }
}

