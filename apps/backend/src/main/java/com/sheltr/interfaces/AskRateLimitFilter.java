package com.sheltr.interfaces;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.RequestPath;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Rejects {@code POST /api/v1/conversations/{id}/ask} from a client address once it exceeds the
 * {@link FixedWindowLimiter} budget. The client key is the socket remote address only;
 * {@code X-Forwarded-For} is never read.
 *
 * <p>The registration in {@code RateLimitConfig} maps the broader servlet pattern
 * {@code /api/v1/conversations/*}, which a servlet mapping can express. This filter narrows it to
 * the ask path itself, so other requests under that prefix pass through uncounted.
 *
 * <p>A filter runs before the MVC dispatcher, so {@link ApiErrorAdvice} cannot see exceptions
 * raised here. The 429 body is therefore written directly, using the same {@link ApiError} shape.
 */
public class AskRateLimitFilter extends OncePerRequestFilter {

    static final String LIMIT_MESSAGE = "Too many questions. Try again shortly.";

    /**
     * Matched with the parser Spring MVC routes with, so the filter sees the path the handler sees:
     * each segment percent-decoded once and matrix parameters ({@code ;x=1}) removed. A raw regex
     * over {@code getRequestURI()} missed {@code /%61sk}, which MVC routes to ask.
     */
    static final PathPattern ASK_PATH = PathPatternParser.defaultInstance.parse("/api/v1/conversations/{id}/ask");

    private final FixedWindowLimiter limiter;
    private final JsonMapper mapper;

    public AskRateLimitFilter(FixedWindowLimiter limiter, JsonMapper mapper) {
        this.limiter = limiter;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equals(request.getMethod()) || !isAskPath(request)) {
            chain.doFilter(request, response);
            return;
        }
        String key = request.getRemoteAddr();
        FixedWindowLimiter.Acquisition acquisition = limiter.acquire(key);
        if (acquisition.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(acquisition.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiError.of("LIMIT_EXCEEDED", LIMIT_MESSAGE));
    }

    /**
     * Matches the path below the context path, parsed as Spring MVC parses it, so the check holds
     * under any deployment prefix and any encoding MVC would still route to ask. Double encoding
     * ({@code %2561sk}) is decoded once, as MVC does, so it neither routes to ask nor is counted.
     */
    private static boolean isAskPath(HttpServletRequest request) {
        RequestPath path = RequestPath.parse(request.getRequestURI(), request.getContextPath());
        return ASK_PATH.matches(path.pathWithinApplication());
    }
}
