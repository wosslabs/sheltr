package com.sheltr.interfaces;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class AskRateLimitFilterTest {

    private static final String ASK_URI = "/api/v1/conversations/7b0f5a52-3f0e-4c1b-9d0e-0a1b2c3d4e5f/ask";

    private static final class FixedClock extends Clock {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(0);
        }
    }

    private final FixedWindowLimiter limiter =
            new FixedWindowLimiter(10, Duration.ofMinutes(1), new FixedClock(), 10_000);
    private final AskRateLimitFilter filter = new AskRateLimitFilter(limiter, JsonMapper.builder().build());

    private static MockHttpServletRequest post(String remoteAddr) {
        var request = new MockHttpServletRequest("POST", ASK_URI);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private static final class CountingChain implements FilterChain {
        int calls;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            calls++;
        }
    }

    @Test
    void eleventhPostFromSameAddressIsRejectedWithoutReachingChain() throws Exception {
        CountingChain chain = new CountingChain();
        for (int i = 0; i < 10; i++) {
            filter.doFilter(post("203.0.113.5"), new MockHttpServletResponse(), chain);
        }
        assertThat(chain.calls).isEqualTo(10);

        var response = new MockHttpServletResponse();
        filter.doFilter(post("203.0.113.5"), response, chain);

        assertThat(chain.calls).isEqualTo(10);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"error\":\"LIMIT_EXCEEDED\"")
                .contains("Too many questions. Try again shortly.")
                .contains("\"timestamp\"");
    }

    @Test
    void differentAddressIsNotAffected() throws Exception {
        CountingChain chain = new CountingChain();
        for (int i = 0; i < 10; i++) {
            filter.doFilter(post("203.0.113.5"), new MockHttpServletResponse(), chain);
        }
        filter.doFilter(post("203.0.113.5"), new MockHttpServletResponse(), chain);

        var response = new MockHttpServletResponse();
        filter.doFilter(post("198.51.100.9"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.calls).isEqualTo(11);
    }

    @Test
    void forwardedForHeaderIsIgnored() throws Exception {
        CountingChain chain = new CountingChain();
        for (int i = 0; i < 10; i++) {
            var request = post("203.0.113.5");
            request.addHeader("X-Forwarded-For", "10.0.0." + i);
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }

        var spoofed = post("203.0.113.5");
        spoofed.addHeader("X-Forwarded-For", "10.9.9.9");
        var response = new MockHttpServletResponse();
        filter.doFilter(spoofed, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(chain.calls).isEqualTo(10);
    }

    @Test
    void getRequestsAreNotCounted() throws Exception {
        CountingChain chain = new CountingChain();
        for (int i = 0; i < 15; i++) {
            var request = new MockHttpServletRequest("GET", ASK_URI);
            request.setRemoteAddr("203.0.113.5");
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }
        assertThat(chain.calls).isEqualTo(15);
    }
}
