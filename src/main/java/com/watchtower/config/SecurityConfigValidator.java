package com.watchtower.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityConfigValidator {

    private final MonitorProperties properties;

    @PostConstruct
    public void validate() {
        MonitorProperties.Security sec = properties.getSecurity();
        if (sec.getDashboardPassword() == null || sec.getDashboardPassword().isBlank()) {
            throw new IllegalStateException(
                    "watchtower.security.dashboard-password is required. " +
                    "Set environment variable WATCHTOWER_DASHBOARD_PASS.");
        }
        if (sec.getAgentApiKey() == null || sec.getAgentApiKey().isBlank()) {
            throw new IllegalStateException(
                    "watchtower.security.agent-api-key is required. " +
                    "Set environment variable WATCHTOWER_AGENT_API_KEY.");
        }
        if (sec.getDashboardPassword().length() < 8) {
            log.warn("dashboard password is shorter than 8 characters; consider a stronger value");
        }
        if (sec.getAgentApiKey().length() < 16) {
            log.warn("agent API key is shorter than 16 characters; consider a stronger value");
        }
    }
}
