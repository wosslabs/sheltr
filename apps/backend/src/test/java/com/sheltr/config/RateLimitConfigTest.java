package com.sheltr.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import com.sheltr.interfaces.AskRateLimitFilter;
import com.sheltr.interfaces.FixedWindowLimiter;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RateLimitConfigTest {

    private static final String ASK_URI = "/api/v1/conversations/7b0f5a52-3f0e-4c1b-9d0e-0a1b2c3d4e5f/ask";

    private final RateLimitConfig config = new RateLimitConfig();

    @Test
    void registrationUsesValidServletPatternAfterSecurityChain() {
        FilterRegistrationBean<AskRateLimitFilter> registration = registration(10);

        assertThat(registration.getUrlPatterns()).containsExactly("/api/v1/conversations/*");
        assertThat(registration.getOrder()).isEqualTo(RateLimitConfig.FILTER_ORDER);
        assertThat(registration.getOrder()).isGreaterThan(-100).isLessThan(Ordered.LOWEST_PRECEDENCE);
    }

    /** Real servlet chain: the registered pattern plus the filter, driven through MockMvc. */
    @Test
    void eleventhAskPostFromSameAddressIsRejectedThroughServletChain() throws Exception {
        FilterRegistrationBean<AskRateLimitFilter> registration = registration(10);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AskStub())
                .addFilter(registration.getFilter(), registration.getUrlPatterns().toArray(String[]::new))
                .build();

        for (int i = 1; i <= 10; i++) {
            mvc.perform(askPost("203.0.113.5")).andExpect(status().isOk());
        }
        mvc.perform(askPost("203.0.113.5"))
                .andExpect(status().is(429))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void askBudgetComesFromLimitProperties() throws Exception {
        FilterRegistrationBean<AskRateLimitFilter> registration = registration(2);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AskStub())
                .addFilter(registration.getFilter(), registration.getUrlPatterns().toArray(String[]::new))
                .build();

        mvc.perform(askPost("203.0.113.5")).andExpect(status().isOk());
        mvc.perform(askPost("203.0.113.5")).andExpect(status().isOk());
        mvc.perform(askPost("203.0.113.5")).andExpect(status().is(429));
    }

    private FilterRegistrationBean<AskRateLimitFilter> registration(int askPerMinute) {
        FixedWindowLimiter limiter = config.askLimiter(new LimitProperties(askPerMinute, 1000));
        return config.askRateLimitFilter(limiter, new DefaultListableBeanFactory().getBeanProvider(JsonMapper.class));
    }

    private static org.springframework.test.web.servlet.RequestBuilder askPost(String remoteAddr) {
        return MockMvcRequestBuilders.post(ASK_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .remoteAddress(remoteAddr);
    }

    @RestController
    static class AskStub {
        @PostMapping("/api/v1/conversations/{id}/ask")
        String ask() {
            return "ok";
        }
    }
}
