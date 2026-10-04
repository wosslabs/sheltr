package com.sheltr.ingestion.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionPropertiesTest {

    @Test
    void nullAllowedHostsBecomesEmptyList() {
        assertThat(new IngestionProperties(null, null, null).allowedHosts()).isEmpty();
    }

    @Test
    void allowedHostsIsCopiedFromSource() {
        List<String> source = new ArrayList<>(List.of("www.ready.gov"));
        IngestionProperties props = new IngestionProperties(source, null, null);

        source.add("evil.example");

        assertThat(props.allowedHosts()).containsExactly("www.ready.gov");
    }

    @Test
    void unsetTimeoutsDefaultToSpecValues() {
        IngestionProperties props = new IngestionProperties(null, null, null);

        assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void explicitTimeoutsAreKept() {
        IngestionProperties props = new IngestionProperties(null, Duration.ofSeconds(3), Duration.ofSeconds(45));

        assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void zeroConnectTimeoutIsRejected() {
        assertThatThrownBy(() -> new IngestionProperties(null, Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect-timeout");
    }

    @Test
    void negativeConnectTimeoutIsRejected() {
        assertThatThrownBy(() -> new IngestionProperties(null, Duration.ofSeconds(-1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect-timeout");
    }

    @Test
    void zeroRequestTimeoutIsRejected() {
        assertThatThrownBy(() -> new IngestionProperties(null, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request-timeout");
    }

    @Test
    void negativeRequestTimeoutIsRejected() {
        assertThatThrownBy(() -> new IngestionProperties(null, null, Duration.ofSeconds(-5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request-timeout");
    }
}
