package org.sgs.walletservice.service;

import org.sgs.walletservice.domain.Wallet;
import org.sgs.walletservice.exception.ForbiddenException;
import org.sgs.walletservice.exception.ResourceNotFoundException;
import org.sgs.walletservice.logging.DomainEvents;
import org.sgs.walletservice.repo.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;

    /** Always starts its own transaction, so a failed insert cannot poison the recovery read. */
    private final TransactionTemplate newTransaction;

    public WalletService(WalletRepository walletRepository, PlatformTransactionManager transactionManager) {
        this.walletRepository = walletRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Wallet getOrCreateWallet(String userId) {
        return getOrCreateWallet(userId, 0L);
    }

    /**
     * Get-or-create, deliberately NOT a single transaction.
     *
     * <p>Two concurrent requests for the same new owner can both see "no wallet" and both insert;
     * the loser trips the unique index on {@code owner_id}. Its transaction is then aborted and
     * Postgres rejects any further statement in it, so the recovery read must happen in a separate
     * transaction - hence the insert is scoped to its own transaction and the retry to another.
     */
    public Wallet getOrCreateWallet(String userId, long initialBalancePaise) {
        if (initialBalancePaise < 0) {
            throw new org.sgs.walletservice.exception.BadRequestException("Initial balance must not be negative");
        }

        Optional<Wallet> existing = walletRepository.findByOwnerId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            return newTransaction.execute(status -> {
                Wallet saved = walletRepository.save(new Wallet(userId, initialBalancePaise));
                log.atInfo()
                        .addKeyValue("event", DomainEvents.WALLET_CREATED)
                        .addKeyValue("owner_id", saved.getOwnerId())
                        .addKeyValue("balance_paise", saved.getBalancePaise())
                        .log("Wallet {} created for owner {}", saved.getId(), saved.getOwnerId());
                return saved;
            });
        } catch (DataIntegrityViolationException ex) {
            // Lost the create race. The duplicate insert blocked until the winner committed, so the
            // winning row is durable and visible to this fresh transaction.
            return newTransaction.execute(status -> walletRepository.findByOwnerId(userId)
                    .orElseThrow(() -> new IllegalStateException("Failed to get or create wallet for user: " + userId)));
        }
    }

    @Transactional(readOnly = true)
    public Wallet getWalletById(Long walletId, String callerId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + walletId));

        if (callerId != null && !wallet.getOwnerId().equals(callerId)) {
            throw new ForbiddenException("Access denied: You do not own wallet " + walletId);
        }

        return wallet;
    }
}
