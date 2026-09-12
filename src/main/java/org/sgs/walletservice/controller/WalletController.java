package org.sgs.walletservice.controller;

import org.sgs.walletservice.domain.Wallet;
import org.sgs.walletservice.dto.CreateWalletRequest;
import org.sgs.walletservice.dto.WalletResponse;
import org.sgs.walletservice.exception.BadRequestException;
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
    public ResponseEntity<WalletResponse> getOrCreateWallet(@RequestBody(required = false) CreateWalletRequest request) {
        if (request == null || request.userId() == null || request.userId().isBlank()) {
            throw new BadRequestException("Request body must contain 'user_id'");
        }
        Wallet wallet = walletService.getOrCreateWallet(request.userId(), request.initialBalancePaise());
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }

    @GetMapping({"/{id}"})
    public ResponseEntity<WalletResponse> getWallet(@PathVariable Long id) {
        Wallet wallet = walletService.getWalletById(id);
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }
}

