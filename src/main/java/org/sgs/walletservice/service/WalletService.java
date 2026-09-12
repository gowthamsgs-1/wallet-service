package org.sgs.walletservice.service;

import org.sgs.walletservice.domain.Wallet;
import org.sgs.walletservice.exception.ForbiddenException;
import org.sgs.walletservice.exception.ResourceNotFoundException;
import org.sgs.walletservice.repo.WalletRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class WalletService {

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
            return walletRepository.save(newWallet);
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

        if (!wallet.getOwnerId().equals(callerId)) {
            throw new ForbiddenException("Access denied: You do not own wallet " + walletId);
        }

        return wallet;
    }
}


