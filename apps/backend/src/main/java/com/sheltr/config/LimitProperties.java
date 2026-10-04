package com.sheltr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Request limits for {@code /ask}. Bound from {@code sheltr.limits.*}.
 *
 * @param askPerMinute  per-client-IP requests per minute (default 10)
 * @param maxAsksPerDay daily cap on user questions, counted since midnight UTC (default 1000)
 */
@ConfigurationProperties("sheltr.limits")
public record LimitProperties(
        @DefaultValue("10") int askPerMinute,
        @DefaultValue("1000") int maxAsksPerDay) {

    public LimitProperties {
        if (askPerMinute < 1) {
            throw new IllegalArgumentException("sheltr.limits.ask-per-minute must be at least 1");
        }
        if (maxAsksPerDay < 1) {
            throw new IllegalArgumentException("sheltr.limits.max-asks-per-day must be at least 1");
        }
    }
}
