package com.mediassistrover.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.bootstrap")
public record AdminBootstrapProperties(
        String adminUsername,
        String adminPassword,
        String adminEmail,
        String adminAccountCode,
        String adminFullName
) {
    public boolean isEmpty() {
        return isBlank(adminUsername) && isBlank(adminPassword) && isBlank(adminEmail)
                && isBlank(adminAccountCode) && isBlank(adminFullName);
    }

    public boolean isComplete() {
        return !isBlank(adminUsername) && !isBlank(adminPassword) && !isBlank(adminEmail)
                && !isBlank(adminAccountCode) && !isBlank(adminFullName);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
