package org.sgs.walletservice.service;

import org.sgs.walletservice.dto.TransferRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stable fingerprint of the business-meaningful parts of a transfer request.
 * Used to detect an idempotency key being reused with a different payload.
 */
@Component
public class TransferFingerprinter {

    public String of(TransferRequest request) {
        return of(request.from(), request.to(), request.amountPaise());
    }

    public String of(Long from, Long to, Long amountPaise) {
        String canonical = from + "|" + to + "|" + amountPaise;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}

