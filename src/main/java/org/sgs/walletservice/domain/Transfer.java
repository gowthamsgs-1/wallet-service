package org.sgs.walletservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "transfers")
@Getter
@Setter
@NoArgsConstructor
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_wallet_id", nullable = false)
    private Long fromWalletId;

    @Column(name = "to_wallet_id", nullable = false)
    private Long toWalletId;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    private String failureReason;

    @Column(nullable = false)
    private String callerId;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Transfer(Long fromWalletId, Long toWalletId, long amountPaise,
                    TransferStatus status, String failureReason,
                    String callerId, String idempotencyKey) {
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amountPaise = amountPaise;
        this.status = status;
        this.failureReason = failureReason;
        this.callerId = callerId;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = Instant.now();
    }
}

