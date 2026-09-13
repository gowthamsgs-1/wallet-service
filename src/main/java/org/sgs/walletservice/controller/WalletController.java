package org.sgs.walletservice.controller;

import jakarta.validation.Valid;
import org.sgs.walletservice.auth.AuthContext;
import org.sgs.walletservice.config.ApiVersions;
import org.sgs.walletservice.domain.Wallet;
import org.sgs.walletservice.dto.CreateWalletRequest;
import org.sgs.walletservice.dto.WalletResponse;
import org.sgs.walletservice.exception.BadRequestException;
import org.sgs.walletservice.service.WalletService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ApiVersions.V1 + "/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping({"", "/"})
    public ResponseEntity<WalletResponse> getOrCreateWallet(@Valid @RequestBody(required = false) CreateWalletRequest request) {
        String userId = AuthContext.getCurrentUser();
        if (userId == null || userId.isBlank()) {
            // Unauthenticated call (e.g. filter not applied): fall back to an explicit user_id
            userId = (request != null) ? request.userId() : null;
        }
        if (userId == null || userId.isBlank()) {
            throw new BadRequestException("'user_id' is required when no bearer token is supplied");
        }
        long initialBalance = (request != null && request.initialBalancePaise() != null)
                ? request.initialBalancePaise()
                : 0L;
        Wallet wallet = walletService.getOrCreateWallet(userId, initialBalance);
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }

    @GetMapping({"/{id}", "/{id}/"})
    public ResponseEntity<WalletResponse> getWallet(@PathVariable Long id) {
        String userId = AuthContext.getCurrentUser();
        Wallet wallet = walletService.getWalletById(id, userId);
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }
}
