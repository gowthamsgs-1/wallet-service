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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final WalletMetrics metrics;

    public TransferService(WalletRepository walletRepository,
                           TransferRepository transferRepository,
                           IdempotencyRecordRepository idempotencyRecordRepository,
                           WalletMetrics metrics) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.metrics = metrics;
    }

    @Transactional(noRollbackFor = InsufficientFundsException.class)
    public Transfer executeTransfer(String callerId, TransferRequest request) {
        if (request.from().equals(request.to())) {
            declined(callerId, request, "same_wallet", "Source and destination wallets must be different");
            throw new BadRequestException("Source and destination wallets must be different");
        }

        if (request.amountPaise() == null || request.amountPaise() <= 0) {
            declined(callerId, request, "non_positive_amount", "Transfer amount must be strictly positive");
            throw new BadRequestException("Transfer amount must be strictly positive");
        }

        // Idempotency check: keys are globally unique, so look up by key alone
        Optional<IdempotencyRecord> existingRecord = idempotencyRecordRepository
                .findByIdempotencyKey(request.idempotencyKey());
        if (existingRecord.isPresent()) {
            IdempotencyRecord record = existingRecord.get();

            // The key belongs to whoever used it first; nobody else may reuse it.
            if (!record.getCallerId().equals(callerId)) {
                declined(callerId, request, "idempotency_key_owned_by_another_caller",
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
                    : fingerprint(existingTransfer.getFromWalletId(), existingTransfer.getToWalletId(), existingTransfer.getAmountPaise());

            // A reused key with a different payload is a conflict, never a second debit.
            if (!storedHash.equals(requestFingerprint(request))) {
                declined(callerId, request, "idempotency_key_payload_mismatch",
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
            event(DomainEvents.TRANSFER_IDEMPOTENT_REPLAY, callerId, request)
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
                declined(callerId, request, "caller_does_not_own_source_wallet",
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
                        failedTransfer.getId(), requestFingerprint(request)));

                metrics.transferDeclinedInsufficientFunds();
                event(DomainEvents.TRANSFER_DECLINED, callerId, request)
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

            event(DomainEvents.TRANSFER_DEBITED, callerId, request)
                    .log("Debited {} paise from wallet {}", request.amountPaise(), fromWallet.getId());

            event(DomainEvents.TRANSFER_CREDITED, callerId, request)
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
                    completedTransfer.getId(), requestFingerprint(request)));

            metrics.transferCreated();
            event(DomainEvents.TRANSFER_CREATED, callerId, request)
                    .addKeyValue("transfer_id", completedTransfer.getId())
                    .addKeyValue("status", completedTransfer.getStatus().name())
                    .log("Transfer {} completed", completedTransfer.getId());

            return completedTransfer;
        } catch (DataIntegrityViolationException ex) {
            // Concurrent execution with the same idempotency key
            IdempotencyRecord raced = idempotencyRecordRepository
                    .findByIdempotencyKey(request.idempotencyKey())
                    .orElseThrow(() -> ex);

            if (!raced.getCallerId().equals(callerId)) {
                declined(callerId, request, "idempotency_key_owned_by_another_caller",
                        "Idempotency key is already in use by another caller");
                throw new ConflictException("Idempotency key '" + request.idempotencyKey()
                        + "' is already in use by another caller");
            }

            metrics.transferIdempotentReplay();
            event(DomainEvents.TRANSFER_IDEMPOTENT_REPLAY, callerId, request)
                    .addKeyValue("transfer_id", raced.getTransferId())
                    .addKeyValue("race", true)
                    .log("Concurrent request lost the idempotency race; returning the winning transfer");

            return transferRepository.findById(raced.getTransferId()).orElseThrow(() -> ex);
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

    /**
     * Starts a log event pre-populated with the fields every transfer event carries.
     * The correlation id is supplied automatically from the MDC by the structured log format.
     */
    private LoggingEventBuilder event(String eventName, String callerId, TransferRequest request) {
        return log.atInfo()
                .addKeyValue("event", eventName)
                .addKeyValue("caller_id", callerId)
                .addKeyValue("from_wallet_id", request.from())
                .addKeyValue("to_wallet_id", request.to())
                .addKeyValue("amount_paise", request.amountPaise())
                .addKeyValue("idempotency_key", request.idempotencyKey());
    }

    private void declined(String callerId, TransferRequest request, String reason, String message) {
        metrics.transferDeclined(reason);
        event(DomainEvents.TRANSFER_DECLINED, callerId, request)
                .addKeyValue("reason", reason)
                .log("Transfer declined: {}", message);
    }

    /**
     * Stable fingerprint of the business-meaningful parts of the request body.
     * Used to detect an idempotency key being reused with a different payload.
     */
    private String requestFingerprint(TransferRequest request) {
        return fingerprint(request.from(), request.to(), request.amountPaise());
    }

    private String fingerprint(Long from, Long to, Long amountPaise) {
        String canonical = from + "|" + to + "|" + amountPaise;
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}

