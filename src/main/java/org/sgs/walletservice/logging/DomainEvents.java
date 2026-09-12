package org.sgs.walletservice.logging;

/**
 * Canonical names for the domain events emitted to the logs.
 *
 * <p>Keeping them in one place means dashboards and alerts can filter on a stable
 * {@code event} field rather than on free-text log messages.
 */
public final class DomainEvents {

    /** A transfer was accepted and persisted as COMPLETED. */
    public static final String TRANSFER_CREATED = "transfer.created";

    /** Funds left the source wallet. */
    public static final String TRANSFER_DEBITED = "transfer.debited";

    /** Funds arrived in the destination wallet. */
    public static final String TRANSFER_CREDITED = "transfer.credited";

    /** A transfer was refused (validation, ownership, funds or idempotency conflict). */
    public static final String TRANSFER_DECLINED = "transfer.declined";

    /** A repeat of an already-processed idempotency key; no new money moved. */
    public static final String TRANSFER_IDEMPOTENT_REPLAY = "transfer.idempotent_replay";

    /** A new wallet was provisioned. */
    public static final String WALLET_CREATED = "wallet.created";

    private DomainEvents() {
    }
}

