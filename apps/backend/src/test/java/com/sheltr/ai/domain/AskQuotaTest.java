package com.sheltr.ai.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AskQuotaTest {

    @Test
    void allowsWhileCountIsBelowTheCap() {
        assertThat(AskQuota.allows(999, 1000)).isTrue();
    }

    @Test
    void refusesOnceCountReachesTheCap() {
        assertThat(AskQuota.allows(1000, 1000)).isFalse();
    }

    @Test
    void refusesEverythingWhenCapIsZero() {
        assertThat(AskQuota.allows(0, 0)).isFalse();
    }
}
