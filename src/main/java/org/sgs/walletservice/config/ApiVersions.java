package org.sgs.walletservice.config;

/**
 * API version path segments.
 *
 * <p>Versions live on the controller, not in configuration: a version bump is a code-level
 * contract change, and v1 and v2 must be able to coexist. Keeping them as constants means
 * {@code grep ApiVersions.V1} lists every endpoint on a given version.
 */
public final class ApiVersions {

    /** Initial public contract. */
    public static final String V1 = "/v1";

    private ApiVersions() {
    }
}

