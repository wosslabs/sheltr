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
import java.util.regex.Pattern;

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

    static final Pattern ASK_PATH = Pattern.compile("^/api/v1/conversations/[^/]+/ask$");

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

    /** Matches against the path below the context path, so the check holds under any deployment prefix. */
    private static boolean isAskPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = uri.substring(contextPath.length());
        return ASK_PATH.matcher(path).matches();
    }
}
