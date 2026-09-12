package org.sgs.walletservice.service;

import org.sgs.walletservice.domain.Wallet;
import org.sgs.walletservice.exception.BadRequestException;
import org.sgs.walletservice.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class WalletService {

    private final Map<Long, Wallet> walletsById = new ConcurrentHashMap<>();
    private final Map<String, Wallet> walletsByUserId = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    public Wallet getOrCreateWallet(String userId, Long initialBalancePaise) {
        if (userId == null || userId.isBlank()) {
            throw new BadRequestException("User ID must not be blank");
        }

        long initialBalance = (initialBalancePaise != null) ? initialBalancePaise : 0L;
        if (initialBalance < 0) {
            throw new BadRequestException("Initial balance must not be negative");
        }

        return walletsByUserId.computeIfAbsent(userId, uid -> {
            long newId = idGenerator.getAndIncrement();
            Wallet wallet = new Wallet(newId, uid, initialBalance);
            walletsById.put(newId, wallet);
            return wallet;
        });
    }

    public Wallet getWalletById(Long id) {
        if (id == null) {
            throw new BadRequestException("Wallet ID must not be null");
        }

        Wallet wallet = walletsById.get(id);
        if (wallet == null) {
            throw new ResourceNotFoundException("Wallet not found with id: " + id);
        }
        return wallet;
    }
}

