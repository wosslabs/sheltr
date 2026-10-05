package com.sheltr.config;

import com.sheltr.interfaces.AskRateLimitFilter;
import com.sheltr.interfaces.FixedWindowLimiter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;

/**
 * Per-client-address limit on {@code POST /api/v1/conversations/{id}/ask}, budget from
 * {@code sheltr.limits.ask-per-minute}. The servlet mapping is the valid prefix pattern; the
 * filter matches the ask path itself.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    /** Servlet mapping. Servlet patterns allow a trailing {@code /*} only, so the ask suffix is matched in the filter. */
    static final String FILTER_PATTERN = "/api/v1/conversations/*";
    static final int MAX_TRACKED_CLIENTS = 10_000;

    /** After Spring Security's chain (order -100), before the MVC dispatcher. */
    static final int FILTER_ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    @Bean
    public FixedWindowLimiter askLimiter(LimitProperties limits) {
        return new FixedWindowLimiter(limits.askPerMinute(), Duration.ofMinutes(1),
                Clock.systemUTC(), MAX_TRACKED_CLIENTS);
    }

    @Bean
    public FilterRegistrationBean<AskRateLimitFilter> askRateLimitFilter(FixedWindowLimiter askLimiter,
                                                                        ObjectProvider<JsonMapper> jsonMapper) {
        JsonMapper mapper = jsonMapper.getIfAvailable(() -> JsonMapper.builder().build());
        FilterRegistrationBean<AskRateLimitFilter> registration =
                new FilterRegistrationBean<>(new AskRateLimitFilter(askLimiter, mapper));
        registration.addUrlPatterns(FILTER_PATTERN);
        registration.setOrder(FILTER_ORDER);
        return registration;
    }
}
