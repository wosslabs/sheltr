package com.sheltr.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitPropertiesTest {

    @Test
    void rejectsAskPerMinuteBelowOne() {
        assertThatThrownBy(() -> new LimitProperties(0, 1000))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMaxAsksPerDayBelowOne() {
        assertThatThrownBy(() -> new LimitProperties(10, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
