package org.sgs.walletservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(
        name = "idempotency_records",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_caller_idempotency_key", columnNames = {"callerId", "idempotencyKey"})
        }
)
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String callerId;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private Long transferId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public IdempotencyRecord(String callerId, String idempotencyKey, Long transferId) {
        this.callerId = callerId;
        this.idempotencyKey = idempotencyKey;
        this.transferId = transferId;
        this.createdAt = Instant.now();
    }
}

