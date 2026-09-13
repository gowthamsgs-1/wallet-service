package org.sgs.walletservice.service;

import org.sgs.walletservice.domain.IdempotencyRecord;
import org.sgs.walletservice.domain.Transfer;
import org.sgs.walletservice.domain.TransferStatus;
import org.sgs.walletservice.domain.Wallet;
import org.sgs.walletservice.dto.TransferRequest;
import org.sgs.walletservice.exception.BadRequestException;
import org.sgs.walletservice.exception.ConflictException;
import org.sgs.walletservice.exception.ForbiddenException;
import org.sgs.walletservice.exception.InsufficientFundsException;
import org.sgs.walletservice.exception.ResourceNotFoundException;
import org.sgs.walletservice.logging.DomainEvents;
import org.sgs.walletservice.metrics.WalletMetrics;
import org.sgs.walletservice.repo.IdempotencyRecordRepository;
import org.sgs.walletservice.repo.TransferRepository;
import org.sgs.walletservice.repo.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Owns the transactional body of a transfer.
 *
 * <p>Living in its own bean means {@link TransferService} can call it through a normal Spring
 * dependency and still get a real transaction - no {@code @Lazy} self-injection needed - while
 * keeping the non-transactional orchestration (idempotency race recovery) outside the boundary.
 */
@Service
public class TransferExecutionService {

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final WalletMetrics metrics;
    private final TransferEventLogger events;
    private final TransferFingerprinter fingerprinter;

    public TransferExecutionService(WalletRepository walletRepository,
                                    TransferRepository transferRepository,
                                    IdempotencyRecordRepository idempotencyRecordRepository,
                                    WalletMetrics metrics,
                                    TransferEventLogger events,
                                    TransferFingerprinter fingerprinter) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.metrics = metrics;
        this.events = events;
        this.fingerprinter = fingerprinter;
    }

    @Transactional(noRollbackFor = InsufficientFundsException.class)
    public Transfer execute(String callerId, TransferRequest request) {
        if (request.from().equals(request.to())) {
            events.declined(callerId, request, "same_wallet", "Source and destination wallets must be different");
            throw new BadRequestException("Source and destination wallets must be different");
        }

        if (request.amountPaise() == null || request.amountPaise() <= 0) {
            events.declined(callerId, request, "non_positive_amount", "Transfer amount must be strictly positive");
            throw new BadRequestException("Transfer amount must be strictly positive");
        }

        // Idempotency check: keys are globally unique, so look up by key alone
        Optional<IdempotencyRecord> existingRecord = idempotencyRecordRepository
                .findByIdempotencyKey(request.idempotencyKey());
        if (existingRecord.isPresent()) {
            return replay(callerId, request, existingRecord.get());
        }

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
            events.declined(callerId, request, "caller_does_not_own_source_wallet",
                    "Caller does not own the source wallet");
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
            idempotencyRecordRepository.save(new IdempotencyRecord(callerId, request.idempotencyKey(),
                    failedTransfer.getId(), fingerprinter.of(request)));

            metrics.transferDeclinedInsufficientFunds();
            events.event(DomainEvents.TRANSFER_DECLINED, callerId, request)
                    .addKeyValue("transfer_id", failedTransfer.getId())
                    .addKeyValue("reason", "insufficient_funds")
                    .addKeyValue("available_paise", fromWallet.getBalancePaise())
                    .log("Transfer declined: {}", reason);

            throw new InsufficientFundsException(reason);
        }

        // Perform transfer
        fromWallet.setBalancePaise(fromWallet.getBalancePaise() - request.amountPaise());
        toWallet.setBalancePaise(toWallet.getBalancePaise() + request.amountPaise());
        walletRepository.save(fromWallet);
        walletRepository.save(toWallet);

        events.event(DomainEvents.TRANSFER_DEBITED, callerId, request)
                .log("Debited {} paise from wallet {}", request.amountPaise(), fromWallet.getId());

        events.event(DomainEvents.TRANSFER_CREDITED, callerId, request)
                .log("Credited {} paise to wallet {}", request.amountPaise(), toWallet.getId());

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
        idempotencyRecordRepository.save(new IdempotencyRecord(callerId, request.idempotencyKey(),
                completedTransfer.getId(), fingerprinter.of(request)));

        metrics.transferCreated();
        events.event(DomainEvents.TRANSFER_CREATED, callerId, request)
                .addKeyValue("transfer_id", completedTransfer.getId())
                .addKeyValue("status", completedTransfer.getStatus().name())
                .log("Transfer {} completed", completedTransfer.getId());

        return completedTransfer;
    }

    /** Returns the outcome already recorded for this idempotency key, without moving money. */
    private Transfer replay(String callerId, TransferRequest request, IdempotencyRecord record) {
        // The key belongs to whoever used it first; nobody else may reuse it.
        if (!record.getCallerId().equals(callerId)) {
            events.declined(callerId, request, "idempotency_key_owned_by_another_caller",
                    "Idempotency key is already in use by another caller");
            throw new ConflictException("Idempotency key '" + request.idempotencyKey()
                    + "' is already in use by another caller");
        }

        Transfer existingTransfer = transferRepository.findById(record.getTransferId())
                .orElseThrow(() -> new IllegalStateException("Transfer record missing for existing idempotency key"));

        // Records created before request fingerprinting existed have a null hash;
        // fall back to the stored transfer's own from/to/amount.
        String storedHash = record.getRequestHash() != null
                ? record.getRequestHash()
                : fingerprinter.of(existingTransfer.getFromWalletId(), existingTransfer.getToWalletId(),
                        existingTransfer.getAmountPaise());

        // A reused key with a different payload is a conflict, never a second debit.
        if (!storedHash.equals(fingerprinter.of(request))) {
            events.declined(callerId, request, "idempotency_key_payload_mismatch",
                    "Idempotency key was already used with a different request payload");
            throw new ConflictException("Idempotency key '" + request.idempotencyKey()
                    + "' was already used with a different request payload");
        }

        // Backfill so the comparison is cheap and exact next time
        if (record.getRequestHash() == null) {
            record.setRequestHash(storedHash);
            idempotencyRecordRepository.save(record);
        }

        metrics.transferIdempotentReplay();
        events.event(DomainEvents.TRANSFER_IDEMPOTENT_REPLAY, callerId, request)
                .addKeyValue("transfer_id", existingTransfer.getId())
                .addKeyValue("original_status", existingTransfer.getStatus().name())
                .log("Idempotent replay hit; returning the original outcome without moving money");

        // Replay the original outcome, including failures
        if (existingTransfer.getStatus() == TransferStatus.FAILED) {
            throw new InsufficientFundsException(existingTransfer.getFailureReason() != null
                    ? existingTransfer.getFailureReason()
                    : "Transfer previously failed for idempotency key " + request.idempotencyKey());
        }

        return existingTransfer;
    }
}

