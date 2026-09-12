package org.sgs.walletservice.controller;

import jakarta.validation.Valid;
import org.sgs.walletservice.auth.AuthContext;
import org.sgs.walletservice.domain.Wallet;
import org.sgs.walletservice.dto.CreateWalletRequest;
import org.sgs.walletservice.dto.WalletResponse;
import org.sgs.walletservice.service.WalletService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> getOrCreateWallet(@Valid @RequestBody(required = false) CreateWalletRequest request) {
        String userId = AuthContext.getCurrentUser();
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



