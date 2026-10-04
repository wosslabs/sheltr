package com.sheltr.interfaces;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Rejects POST requests from a client address once it exceeds the {@link FixedWindowLimiter}
 * budget. The client key is the socket remote address only; {@code X-Forwarded-For} is never read.
 *
 * <p>A filter runs before the MVC dispatcher, so {@link ApiErrorAdvice} cannot see exceptions
 * raised here. The 429 body is therefore written directly, using the same {@link ApiError} shape.
 * Only POST is counted; the URL pattern is applied by the registration in {@code RateLimitConfig}.
 */
public class AskRateLimitFilter extends OncePerRequestFilter {

    static final String LIMIT_MESSAGE = "Too many questions. Try again shortly.";

    private final FixedWindowLimiter limiter;
    private final JsonMapper mapper;

    public AskRateLimitFilter(FixedWindowLimiter limiter, JsonMapper mapper) {
        this.limiter = limiter;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String key = request.getRemoteAddr();
        if (limiter.tryAcquire(key)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(limiter.retryAfterSeconds(key)));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiError.of("LIMIT_EXCEEDED", LIMIT_MESSAGE));
    }
}
