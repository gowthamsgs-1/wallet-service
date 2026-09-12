package org.sgs.walletservice.repo;

import org.sgs.walletservice.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {
    Optional<IdempotencyRecord> findByCallerIdAndIdempotencyKey(String callerId, String idempotencyKey);
}

