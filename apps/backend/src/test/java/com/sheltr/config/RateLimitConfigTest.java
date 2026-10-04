package com.sheltr.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.sheltr.interfaces.AskRateLimitFilter;
import com.sheltr.interfaces.FixedWindowLimiter;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitConfigTest {

    private static final String ASK_URI = "/api/v1/conversations/7b0f5a52-3f0e-4c1b-9d0e-0a1b2c3d4e5f/ask";

    private final RateLimitConfig config = new RateLimitConfig();

    @Test
    void registrationCoversAskPathAfterSecurityChain() {
        FixedWindowLimiter limiter = config.askLimiter(new LimitProperties(10, 1000));
        FilterRegistrationBean<AskRateLimitFilter> registration = config.askRateLimitFilter(
                limiter, new DefaultListableBeanFactory().getBeanProvider(JsonMapper.class));

        assertThat(registration.getUrlPatterns()).containsExactly(RateLimitConfig.ASK_PATH);
        assertThat(registration.getOrder()).isEqualTo(RateLimitConfig.FILTER_ORDER);
        assertThat(registration.getOrder()).isGreaterThan(-100).isLessThan(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void askBudgetComesFromLimitProperties() throws Exception {
        FixedWindowLimiter limiter = config.askLimiter(new LimitProperties(2, 1000));
        FilterRegistrationBean<AskRateLimitFilter> registration = config.askRateLimitFilter(
                limiter, new DefaultListableBeanFactory().getBeanProvider(JsonMapper.class));
        for (int i = 0; i < 2; i++) {
            var request = new MockHttpServletRequest("POST", ASK_URI);
            request.setRemoteAddr("203.0.113.5");
            registration.getFilter().doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }
        var request = new MockHttpServletRequest("POST", ASK_URI);
        request.setRemoteAddr("203.0.113.5");
        var response = new MockHttpServletResponse();
        registration.getFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
    }
}
