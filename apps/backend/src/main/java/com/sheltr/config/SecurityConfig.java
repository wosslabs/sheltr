package com.sheltr.config;

import com.sheltr.interfaces.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * HTTP Basic with role {@code ADMIN} on {@code /api/v1/admin/**}. Actuator health and info are
 * public; every other actuator path is denied. All remaining routes (the citizen API) are public.
 *
 * <p>Unauthenticated admin calls get 401 with {@code WWW-Authenticate: Basic} and an
 * {@link ApiError} body; authenticated non-admins get 403 with an {@link ApiError} body. Bodies are
 * written with the application's {@link JsonMapper}, the one Spring MVC uses for {@code ApiError}.
 *
 * <p>The single admin user comes from {@link AdminCredentials#load}. Declaring the
 * {@link UserDetailsService} bean stops Spring Boot from generating and logging a password.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    static final String ADMIN_ROLE = "ADMIN";
    static final String REALM = "Realm";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectProvider<JsonMapper> jsonMapper)
            throws Exception {
        JsonMapper mapper = jsonMapper.getIfAvailable(() -> JsonMapper.builder().build());
        AuthenticationEntryPoint entryPoint = (request, response, exception) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + REALM + "\"");
            writeError(response, mapper, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required.");
        };
        AccessDeniedHandler accessDenied = (request, response, exception) ->
                writeError(response, mapper, HttpStatus.FORBIDDEN, "FORBIDDEN", "Admin role required.");

        return http
                // CSRF off: stateless HTTP Basic API with no cookies or browser form posts, so there is no ambient credential to forge.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/admin/**").hasRole(ADMIN_ROLE)
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .anyRequest().permitAll())
                .httpBasic(basic -> basic.realmName(REALM).authenticationEntryPoint(entryPoint))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDenied))
                .build();
    }

    private static void writeError(HttpServletResponse response, JsonMapper mapper, HttpStatus status,
                                   String error, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiError.of(error, message));
    }

    /** Delegating encoder: stored values carry an id prefix such as {@code {bcrypt}}; new encodings use bcrypt. */
    @Bean
    public static PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Exactly one user, the admin. The credential is read from the environment, validated and
     * encoded inside {@link AdminCredentials#load}, so startup fails on misconfiguration and only
     * the encoded password is kept (in the returned manager). See that method for the accepted
     * residual: the Environment property source still holds the plaintext.
     */
    @Bean
    public UserDetailsService adminUserDetailsService(Environment environment, PasswordEncoder passwordEncoder) {
        AdminCredentials admin = AdminCredentials.load(environment, passwordEncoder);
        return new InMemoryUserDetailsManager(User.withUsername(admin.username())
                .password(admin.encodedPassword())
                .roles(ADMIN_ROLE)
                .build());
    }
}
