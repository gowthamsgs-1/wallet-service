package org.sgs.walletservice.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.sgs.walletservice.config.ApiProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class BearerTokenFilter extends OncePerRequestFilter {

    private final TokenService tokenService;
    private final String securedPathPrefix;
    private final List<String> publicPaths;

    public BearerTokenFilter(TokenService tokenService, ApiProperties apiProperties) {
        this.tokenService = tokenService;
        this.securedPathPrefix = apiProperties.getSecuredPathPrefix();
        this.publicPaths = List.copyOf(apiProperties.getPublicPaths());
    }

    /**
     * Deny by default: everything under the service base path is authenticated regardless of API
     * version, so adding /v2 (or any new resource) is protected because nothing was done, rather
     * than unprotected because something was forgotten. Actuator lives at the root
     * (/health, /metrics), outside the base path, and is therefore untouched.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith(securedPathPrefix)) {
            return true;
        }
        return publicPaths.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeUnauthorizedResponse(response, "Missing or invalid Authorization header. Expected 'Bearer <token>'");
            return;
        }

        String rawToken = authHeader.substring(7).trim();
        Optional<String> userIdOpt = tokenService.resolveUserId(rawToken);

        if (userIdOpt.isEmpty()) {
            writeUnauthorizedResponse(response, "Invalid or unrecognized bearer token");
            return;
        }

        String userId = userIdOpt.get();
        AuthContext.setCurrentUser(userId);
        request.setAttribute("authUserId", userId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    private void writeUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String escaped = message.replace("\"", "\\\"");
        String json = String.format("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"%s\",\"timestamp\":\"%s\"}", escaped, Instant.now());
        response.getWriter().write(json);
    }
}


