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
                @UniqueConstraint(name = "uk_idempotency_key", columnNames = {"idempotencyKey"})
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

    /**
     * Globally unique across all callers: one key may only ever describe one transfer.
     */
    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private Long transferId;

    /**
     * Fingerprint of the request payload (from/to/amount) that first used this key.
     * A replay with a different fingerprint is a conflict, not a retry.
     */
    @Column(length = 128)
    private String requestHash;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public IdempotencyRecord(String callerId, String idempotencyKey, Long transferId) {
        this(callerId, idempotencyKey, transferId, null);
    }

    public IdempotencyRecord(String callerId, String idempotencyKey, Long transferId, String requestHash) {
        this.callerId = callerId;
        this.idempotencyKey = idempotencyKey;
        this.transferId = transferId;
        this.requestHash = requestHash;
        this.createdAt = Instant.now();
    }
}
