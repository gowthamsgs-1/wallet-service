package org.sgs.walletservice.controller;

import jakarta.validation.Valid;
import org.sgs.walletservice.auth.AuthContext;
import org.sgs.walletservice.domain.Transfer;
import org.sgs.walletservice.dto.TransferRequest;
import org.sgs.walletservice.dto.TransferResponse;
import org.sgs.walletservice.service.TransferService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(@Valid @RequestBody TransferRequest request) {
        String callerId = AuthContext.getCurrentUser();
        Transfer transfer = transferService.executeTransfer(callerId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(TransferResponse.from(transfer));
    }

    @GetMapping({"/{id}", "/{id}/"})
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable Long id) {
        String callerId = AuthContext.getCurrentUser();
        Transfer transfer = transferService.getTransferById(id, callerId);
        return ResponseEntity.ok(TransferResponse.from(transfer));
    }
}


