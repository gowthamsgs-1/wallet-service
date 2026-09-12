package org.sgs.walletservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "wallets", indexes = {
        @Index(name = "idx_wallets_owner_id", columnList = "ownerId", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String ownerId;

    @Column(nullable = false)
    private long balancePaise;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Wallet(String ownerId, long initialBalancePaise) {
        this.ownerId = ownerId;
        this.balancePaise = initialBalancePaise;
        this.createdAt = Instant.now();
    }
}

