package org.sgs.walletservice.repo;

import org.sgs.walletservice.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {
    /**
     * Idempotency keys are globally unique, so the caller is not part of the lookup.
     */
    Optional<IdempotencyRecord> findByIdempotencyKey(String idempotencyKey);
}
