package org.sgs.walletservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.method.HandlerTypePredicate;

/**
 * Applies the global service context root ({@code /wallet-service}) to every REST controller.
 *
 * <p>Only the base path is global. The version segment is declared by each controller
 * ({@link ApiVersions}), so introducing v2 is additive: {@code /wallet-service/v1/transfers} keeps
 * serving existing clients while {@code /wallet-service/v2/transfers} ships alongside it.
 * The predicate is scoped to our controller package so Actuator endpoints (/health, /metrics)
 * stay at the root.
 */
@Configuration
@EnableConfigurationProperties(ApiProperties.class)
public class ApiPathConfig implements WebMvcConfigurer {

    private final ApiProperties apiProperties;

    public ApiPathConfig(ApiProperties apiProperties) {
        this.apiProperties = apiProperties;
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(
                apiProperties.getBasePath(),
                HandlerTypePredicate.forBasePackage("org.sgs.walletservice.controller"));
    }
}



