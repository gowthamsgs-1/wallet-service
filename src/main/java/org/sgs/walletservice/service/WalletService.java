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
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional
    public Wallet getOrCreateWallet(String userId) {
        return getOrCreateWallet(userId, 0L);
    }

    @Transactional
    public Wallet getOrCreateWallet(String userId, long initialBalancePaise) {
        if (initialBalancePaise < 0) {
            throw new org.sgs.walletservice.exception.BadRequestException("Initial balance must not be negative");
        }

        Optional<Wallet> existing = walletRepository.findByOwnerId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            Wallet newWallet = new Wallet(userId, initialBalancePaise);
            Wallet saved = walletRepository.save(newWallet);
            log.atInfo()
                    .addKeyValue("event", DomainEvents.WALLET_CREATED)
                    .addKeyValue("owner_id", saved.getOwnerId())
                    .addKeyValue("balance_paise", saved.getBalancePaise())
                    .log("Wallet {} created for owner {}", saved.getId(), saved.getOwnerId());
            return saved;
        } catch (DataIntegrityViolationException ex) {
            // In case of concurrent creation, return the one that succeeded
            return walletRepository.findByOwnerId(userId)
                    .orElseThrow(() -> new IllegalStateException("Failed to get or create wallet for user: " + userId));
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
