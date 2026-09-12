package org.sgs.walletservice.service;

import org.sgs.walletservice.domain.IdempotencyRecord;
import org.sgs.walletservice.domain.Transfer;
import org.sgs.walletservice.domain.TransferStatus;
import org.sgs.walletservice.domain.Wallet;
import org.sgs.walletservice.dto.TransferRequest;
import org.sgs.walletservice.exception.BadRequestException;
import org.sgs.walletservice.exception.ForbiddenException;
import org.sgs.walletservice.exception.InsufficientFundsException;
import org.sgs.walletservice.exception.ResourceNotFoundException;
import org.sgs.walletservice.repo.IdempotencyRecordRepository;
import org.sgs.walletservice.repo.TransferRepository;
import org.sgs.walletservice.repo.WalletRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class TransferService {

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;

    public TransferService(WalletRepository walletRepository,
                           TransferRepository transferRepository,
                           IdempotencyRecordRepository idempotencyRecordRepository) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
    }

    @Transactional(noRollbackFor = InsufficientFundsException.class)
    public Transfer executeTransfer(String callerId, TransferRequest request) {
        if (request.from().equals(request.to())) {
            throw new BadRequestException("Source and destination wallets must be different");
        }

        if (request.amountPaise() == null || request.amountPaise() <= 0) {
            throw new BadRequestException("Transfer amount must be strictly positive");
        }

        // Idempotency check: if already processed for this caller and idempotency key, return existing
        Optional<IdempotencyRecord> existingRecord = idempotencyRecordRepository
                .findByCallerIdAndIdempotencyKey(callerId, request.idempotencyKey());
        if (existingRecord.isPresent()) {
            return transferRepository.findById(existingRecord.get().getTransferId())
                    .orElseThrow(() -> new IllegalStateException("Transfer record missing for existing idempotency key"));
        }

        try {
            // Lock both wallets in deterministic order (ascending ID) to prevent deadlocks
            List<Long> idsToLock = Stream.of(request.from(), request.to()).sorted().toList();
            List<Wallet> lockedWallets = walletRepository.findAndLockByIdInOrderByIdAsc(idsToLock);

            if (lockedWallets.size() < 2) {
                throw new ResourceNotFoundException("One or both wallets not found");
            }

            Wallet fromWallet = lockedWallets.stream()
                    .filter(w -> w.getId().equals(request.from()))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Source wallet not found: " + request.from()));

            Wallet toWallet = lockedWallets.stream()
                    .filter(w -> w.getId().equals(request.to()))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Destination wallet not found: " + request.to()));

            // Verify caller owns the source wallet
            if (!fromWallet.getOwnerId().equals(callerId)) {
                throw new ForbiddenException("Access denied: Caller '" + callerId + "' does not own wallet " + request.from());
            }

            // Check sufficient balance
            if (fromWallet.getBalancePaise() < request.amountPaise()) {
                String reason = "Insufficient funds in wallet " + request.from() +
                        ". Available: " + fromWallet.getBalancePaise() + " paise, Required: " + request.amountPaise() + " paise";

                Transfer failedTransfer = new Transfer(
                        request.from(),
                        request.to(),
                        request.amountPaise(),
                        TransferStatus.FAILED,
                        reason,
                        callerId,
                        request.idempotencyKey()
                );
                failedTransfer = transferRepository.save(failedTransfer);
                idempotencyRecordRepository.save(new IdempotencyRecord(callerId, request.idempotencyKey(), failedTransfer.getId()));

                throw new InsufficientFundsException(reason);
            }

            // Perform transfer
            fromWallet.setBalancePaise(fromWallet.getBalancePaise() - request.amountPaise());
            toWallet.setBalancePaise(toWallet.getBalancePaise() + request.amountPaise());
            walletRepository.save(fromWallet);
            walletRepository.save(toWallet);

            Transfer completedTransfer = new Transfer(
                    request.from(),
                    request.to(),
                    request.amountPaise(),
                    TransferStatus.COMPLETED,
                    null,
                    callerId,
                    request.idempotencyKey()
            );
            completedTransfer = transferRepository.save(completedTransfer);
            idempotencyRecordRepository.save(new IdempotencyRecord(callerId, request.idempotencyKey(), completedTransfer.getId()));

            return completedTransfer;
        } catch (DataIntegrityViolationException ex) {
            // Concurrent execution with identical idempotency key
            return idempotencyRecordRepository.findByCallerIdAndIdempotencyKey(callerId, request.idempotencyKey())
                    .flatMap(rec -> transferRepository.findById(rec.getTransferId()))
                    .orElseThrow(() -> ex);
        }
    }

    @Transactional(readOnly = true)
    public Transfer getTransferById(Long transferId, String callerId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found with id: " + transferId));

        // Allow caller if caller initiated it, or owns either fromWallet or toWallet
        boolean isCaller = callerId.equals(transfer.getCallerId());
        boolean isParty = false;
        if (!isCaller) {
            Optional<Wallet> fromOpt = walletRepository.findById(transfer.getFromWalletId());
            Optional<Wallet> toOpt = walletRepository.findById(transfer.getToWalletId());
            isParty = (fromOpt.isPresent() && callerId.equals(fromOpt.get().getOwnerId()))
                    || (toOpt.isPresent() && callerId.equals(toOpt.get().getOwnerId()));
        }

        if (!isCaller && !isParty) {
            throw new ForbiddenException("Access denied: You do not have permission to view transfer " + transferId);
        }

        return transfer;
    }
}

