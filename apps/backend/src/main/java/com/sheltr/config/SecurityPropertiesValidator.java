package com.sheltr.config;

import org.springframework.stereotype.Component;

/**
 * Runs the credential check while the context starts, so a misconfigured deployment fails at
 * startup instead of serving requests. Applies to every profile.
 */
@Component
class SecurityPropertiesValidator {

    SecurityPropertiesValidator(SecurityProperties properties) {
        properties.validated();
    }
}
