package com.sheltr.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.regex.Pattern;

/**
 * HTTP Basic with role {@code ADMIN} on {@code /api/v1/admin/**}; every other route (the citizen
 * API, actuator health and info) is public. Unauthenticated admin calls get the default Basic entry
 * point (401 with {@code WWW-Authenticate}); authenticated non-admins get 403.
 *
 * <p>The single admin user comes from {@link SecurityProperties}. Declaring the
 * {@link UserDetailsService} bean stops Spring Boot from generating and logging a password.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    static final String ADMIN_ROLE = "ADMIN";

    private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]?\\$\\d{2}\\$[./A-Za-z0-9]{53}$");
    private static final String BCRYPT_PREFIX = "{bcrypt}";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // CSRF off: stateless HTTP Basic API with no cookies or browser form posts, so there is no ambient credential to forge.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/admin/**").hasRole(ADMIN_ROLE)
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    /** Delegating encoder: stored values carry an id prefix such as {@code {bcrypt}}; new encodings use bcrypt. */
    @Bean
    public static PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Exactly one user, the admin, built from {@link SecurityProperties}. Fails startup when the
     * credentials are missing, ambiguous, or the hash is not bcrypt.
     */
    @Bean
    public UserDetailsService adminUserDetailsService(SecurityProperties properties, PasswordEncoder passwordEncoder) {
        return new InMemoryUserDetailsManager(adminUser(properties.validated(), passwordEncoder));
    }

    static UserDetails adminUser(SecurityProperties properties, PasswordEncoder passwordEncoder) {
        return User.withUsername(properties.username())
                .password(storedPassword(properties, passwordEncoder))
                .roles(ADMIN_ROLE)
                .build();
    }

    /**
     * A plaintext password is bcrypt-encoded here and not kept by this class; a hash is used as given.
     * Neither value is logged or included in exception messages.
     */
    private static String storedPassword(SecurityProperties properties, PasswordEncoder passwordEncoder) {
        if (properties.password() != null) {
            return passwordEncoder.encode(properties.password());
        }
        String hash = properties.passwordHash();
        String raw = hash.startsWith(BCRYPT_PREFIX) ? hash.substring(BCRYPT_PREFIX.length()) : hash;
        if (!BCRYPT.matcher(raw).matches()) {
            throw new IllegalStateException(
                    "SHELTR_ADMIN_PASSWORD_HASH must be a bcrypt hash ($2a$/$2b$/$2y$, optionally prefixed with {bcrypt})");
        }
        return BCRYPT_PREFIX + raw;
    }
}
