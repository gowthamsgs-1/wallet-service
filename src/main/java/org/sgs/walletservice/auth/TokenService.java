package org.sgs.walletservice.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@ConfigurationProperties(prefix = "wallet")
public class TokenService {

    private Map<String, String> demoTokens = new HashMap<>();

    public Map<String, String> getDemoTokens() {
        return demoTokens;
    }

    public void setDemoTokens(Map<String, String> demoTokens) {
        this.demoTokens = demoTokens;
    }

    public Optional<String> resolveUserId(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }

        String token = rawToken.trim();

        // 1. Exact match in demo tokens
        if (demoTokens != null && demoTokens.containsKey(token)) {
            return Optional.ofNullable(demoTokens.get(token));
        }

        // 2. Fallback: if token starts with "tok-", strip prefix
        if (token.startsWith("tok-") && token.length() > 4) {
            return Optional.of(token.substring(4));
        }

        // 3. Fallback: treat the bearer token itself directly as the userId
        return Optional.of(token);
    }
}

