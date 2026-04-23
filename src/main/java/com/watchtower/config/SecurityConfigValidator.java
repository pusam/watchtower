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

        boolean hasAgents = sec.getAgents() != null && !sec.getAgents().isEmpty();
        boolean hasLegacyKey = sec.getAgentApiKey() != null && !sec.getAgentApiKey().isBlank();
        if (!hasAgents && !hasLegacyKey) {
            throw new IllegalStateException(
                    "Agent authentication not configured. Either define " +
                    "watchtower.security.agents[] (recommended: per-agent HMAC secrets) " +
                    "or set WATCHTOWER_AGENT_API_KEY for legacy single-key mode.");
        }
        if (hasAgents) {
            for (int i = 0; i < sec.getAgents().size(); i++) {
                MonitorProperties.AgentCredential a = sec.getAgents().get(i);
                if (a.getId() == null || a.getId().isBlank()) {
                    throw new IllegalStateException(
                            "watchtower.security.agents[" + i + "].id is required");
                }
                if (a.getHmacSecret() == null || a.getHmacSecret().length() < 32) {
                    throw new IllegalStateException(
                            "watchtower.security.agents[" + i + "].hmac-secret must be >= 32 chars " +
                            "(generate: openssl rand -hex 32)");
                }
                if (a.getPreviousHmacSecrets() != null) {
                    for (int j = 0; j < a.getPreviousHmacSecrets().size(); j++) {
                        String prev = a.getPreviousHmacSecrets().get(j);
                        if (prev == null || prev.length() < 32) {
                            throw new IllegalStateException(
                                    "watchtower.security.agents[" + i + "].previous-hmac-secrets["
                                    + j + "] must be >= 32 chars");
                        }
                    }
                }
            }
        }
        if (hasLegacyKey && sec.isAllowLegacyApiKey() && sec.getAgentApiKey().length() < 16) {
            log.warn("legacy agent API key is shorter than 16 characters; consider a stronger value " +
                    "or migrate to per-agent HMAC (watchtower.security.agents[])");
        }
        if (hasLegacyKey && sec.isAllowLegacyApiKey()) {
            log.warn("legacy X-API-Key authentication is enabled. For production, " +
                    "set watchtower.security.allow-legacy-api-key=false after migrating all agents.");
        }
        if (sec.getDashboardPassword().length() < 8) {
            log.warn("dashboard password is shorter than 8 characters; consider a stronger value");
        }
    }
}
