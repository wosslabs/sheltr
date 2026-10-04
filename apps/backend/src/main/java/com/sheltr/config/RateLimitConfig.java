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
 * Per-client-address limit on {@code POST /api/v1/conversations/*}{@code /ask}, budget from
 * {@code sheltr.limits.ask-per-minute}.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    static final String ASK_PATH = "/api/v1/conversations/*/ask";
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
        registration.addUrlPatterns(ASK_PATH);
        registration.setOrder(FILTER_ORDER);
        return registration;
    }
}
