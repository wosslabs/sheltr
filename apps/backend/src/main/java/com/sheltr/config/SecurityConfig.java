package com.sheltr.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TEMPORARY placeholder added with the security starter (Phase 0 Task 1). Spring Security's default
 * chain would return 401 for every route; this keeps the pre-existing open behaviour so the suite stays
 * green. Task 4 replaces it with HTTP Basic on {@code /api/v1/admin/**} and CSRF disabled.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
