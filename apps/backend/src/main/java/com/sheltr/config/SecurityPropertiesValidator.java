package com.sheltr.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Runs the credential check while the context starts, so a misconfigured non-local
 * deployment fails at startup instead of serving requests.
 */
@Component
class SecurityPropertiesValidator {

    SecurityPropertiesValidator(SecurityProperties properties, Environment environment) {
        properties.validated(environment.acceptsProfiles(Profiles.of("local")));
    }
}
