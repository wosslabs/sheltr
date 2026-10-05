package com.sheltr.search.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CountryBoostTest {

    private final CountryBoost boost = new CountryBoost(0.15);

    @Test
    void matchingCountryIsBoosted() {
        assertThat(boost.factor("GB", "GB")).isEqualTo(1.15);
    }

    @Test
    void globalAndOtherCountryPassagesAreNotBoosted() {
        assertThat(boost.factor(null, "GB")).isEqualTo(1.0);
        assertThat(boost.factor("US", "GB")).isEqualTo(1.0);
    }

    @Test
    void noQueryCountryMeansNoBoost() {
        assertThat(boost.factor("GB", null)).isEqualTo(1.0);
    }
}
