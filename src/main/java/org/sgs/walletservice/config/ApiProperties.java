package org.sgs.walletservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Externalised API path settings.
 *
 * <p>Only the service context root is global. The API version is deliberately NOT here: it is
 * declared per controller (see {@link ApiVersions}) so that v1 and v2 can be served side by side
 * instead of a version bump silently 404-ing every existing client.
 */
@ConfigurationProperties(prefix = "wallet.api")
public class ApiProperties {

    /** Service context root applied to every controller, e.g. {@code /wallet-service}. */
    private String basePath = "/wallet-service";

    /**
     * Paths under {@link #basePath} that do NOT require a bearer token.
     * Everything under the base path is authenticated unless listed here.
     */
    private List<String> publicPaths = new ArrayList<>();

    public String getBasePath() {
        return normalize(basePath);
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    /**
     * Everything at or below this prefix is part of the versioned API and is authenticated.
     * Trailing slash included so {@code /wallet-service-internal} cannot match by accident.
     */
    public String getSecuredPathPrefix() {
        return getBasePath() + "/";
    }

    /** Leading-slashed, never trailing-slashed, so segments always concatenate cleanly. */
    private String normalize(String segment) {
        if (segment == null || segment.isBlank()) {
            return "";
        }
        String trimmed = segment.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
