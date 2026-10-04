package com.sheltr.ingestion.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionPropertiesTest {

    @Test
    void nullAllowedHostsBecomesEmptyList() {
        assertThat(new IngestionProperties(null).allowedHosts()).isEmpty();
    }

    @Test
    void allowedHostsIsCopiedFromSource() {
        List<String> source = new ArrayList<>(List.of("www.ready.gov"));
        IngestionProperties props = new IngestionProperties(source);

        source.add("evil.example");

        assertThat(props.allowedHosts()).containsExactly("www.ready.gov");
    }
}
