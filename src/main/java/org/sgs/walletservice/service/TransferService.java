package org.sgs.walletservice.service;

import org.sgs.walletservice.domain.IdempotencyRecord;
import org.sgs.walletservice.domain.Transfer;
import org.sgs.walletservice.domain.TransferStatus;
import org.sgs.walletservice.domain.Wallet;
import org.sgs.walletservice.dto.TransferRequest;
import org.sgs.walletservice.exception.ConflictException;
import org.sgs.walletservice.exception.ForbiddenException;
import org.sgs.walletservice.exception.InsufficientFundsException;
import org.sgs.walletservice.exception.ResourceNotFoundException;
import org.sgs.walletservice.logging.DomainEvents;
import org.sgs.walletservice.metrics.WalletMetrics;
import org.sgs.walletservice.repo.IdempotencyRecordRepository;
import org.sgs.walletservice.repo.TransferRepository;
import org.sgs.walletservice.repo.WalletRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Orchestrates transfers. Deliberately holds no transaction of its own: the money movement lives in
 * {@link TransferExecutionService}, a separate bean so the proxy is crossed on every call.
 */
@Service
public class TransferService {

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final WalletMetrics metrics;
    private final TransferEventLogger events;
    private final TransferExecutionService executor;

    public TransferService(WalletRepository walletRepository,
                           TransferRepository transferRepository,
                           IdempotencyRecordRepository idempotencyRecordRepository,
                           WalletMetrics metrics,
                           TransferEventLogger events,
                           TransferExecutionService executor) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.metrics = metrics;
        this.events = events;
        this.executor = executor;
    }

    /**
     * Entry point, deliberately NOT transactional.
     *
     * <p>Two requests can pass the idempotency pre-check simultaneously; the loser then trips the
     * unique index on {@code idempotency_key}. At that point its transaction is already aborted and
     * Postgres refuses any further statement in it, so recovery cannot happen inside. Catching here,
     * outside the transactional boundary, means the failed transaction has rolled back and closed
     * and the lookups in {@link #recoverFromIdempotencyRace} run in fresh transactions.
     */
    public Transfer executeTransfer(String callerId, TransferRequest request) {
        try {
            return executor.execute(callerId, request);
        } catch (DataIntegrityViolationException ex) {
            return recoverFromIdempotencyRace(callerId, request, ex);
        }
    }

    /**
     * Recovers from losing the race on the unique {@code idempotency_key} index.
     *
     * <p>Runs only after the failed transaction has rolled back and closed, so these lookups start
     * fresh transactions and can see the row the winner committed. The duplicate INSERT blocks until
     * the winner commits or aborts, so by the time we get here the winning record is durable.
     */
    private Transfer recoverFromIdempotencyRace(String callerId, TransferRequest request,
                                                DataIntegrityViolationException ex) {
        IdempotencyRecord raced = idempotencyRecordRepository
                .findByIdempotencyKey(request.idempotencyKey())
                .orElseThrow(() -> ex);

        if (!raced.getCallerId().equals(callerId)) {
            events.declined(callerId, request, "idempotency_key_owned_by_another_caller",
                    "Idempotency key is already in use by another caller");
            throw new ConflictException("Idempotency key '" + request.idempotencyKey()
                    + "' is already in use by another caller");
        }

        Transfer winner = transferRepository.findById(raced.getTransferId()).orElseThrow(() -> ex);

        metrics.transferIdempotentReplay();
        events.event(DomainEvents.TRANSFER_IDEMPOTENT_REPLAY, callerId, request)
                .addKeyValue("transfer_id", winner.getId())
                .addKeyValue("original_status", winner.getStatus().name())
                .addKeyValue("race", true)
                .log("Concurrent request lost the idempotency race; returning the winning transfer");

        // Replay the winner's outcome, including failures, exactly like a sequential replay.
        if (winner.getStatus() == TransferStatus.FAILED) {
            throw new InsufficientFundsException(winner.getFailureReason() != null
                    ? winner.getFailureReason()
                    : "Transfer previously failed for idempotency key " + request.idempotencyKey());
        }

        return winner;
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
