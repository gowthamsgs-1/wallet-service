package org.sgs.walletservice.repo;

import org.sgs.walletservice.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, Long> {
}

