package com.watchtower.config;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigValidatorTest {

    @Test
    void validate_throws_whenDashboardPasswordBlank() {
        MonitorProperties props = new MonitorProperties();
        props.getSecurity().setAgentApiKey("some-long-agent-key-123456");
        props.getSecurity().setDashboardPassword("");
        SecurityConfigValidator v = new SecurityConfigValidator(props);
        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dashboard-password");
    }

    @Test
    void validate_throws_whenNoAgentAuthConfigured() {
        MonitorProperties props = new MonitorProperties();
        props.getSecurity().setDashboardPassword("strong-password");
        props.getSecurity().setAgentApiKey("");
        SecurityConfigValidator v = new SecurityConfigValidator(props);
        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Agent authentication");
    }

    @Test
    void validate_passes_withLegacyKey() {
        MonitorProperties props = new MonitorProperties();
        props.getSecurity().setDashboardPassword("strong-password");
        props.getSecurity().setAgentApiKey("long-enough-agent-api-key-123456");
        SecurityConfigValidator v = new SecurityConfigValidator(props);
        v.validate();
        assertThat(props.getSecurity().getDashboardPassword()).isNotBlank();
    }

    @Test
    void validate_passes_withPerAgentCredentials() {
        MonitorProperties props = new MonitorProperties();
        props.getSecurity().setDashboardPassword("strong-password");
        props.getSecurity().setAgentApiKey("");
        MonitorProperties.AgentCredential a = new MonitorProperties.AgentCredential();
        a.setId("server-a");
        a.setHmacSecret("0123456789abcdef0123456789abcdef"); // 32 chars
        props.getSecurity().setAgents(List.of(a));
        SecurityConfigValidator v = new SecurityConfigValidator(props);
        v.validate();
    }

    @Test
    void validate_throws_whenAgentSecretTooShort() {
        MonitorProperties props = new MonitorProperties();
        props.getSecurity().setDashboardPassword("strong-password");
        props.getSecurity().setAgentApiKey("");
        MonitorProperties.AgentCredential a = new MonitorProperties.AgentCredential();
        a.setId("server-a");
        a.setHmacSecret("too-short");
        props.getSecurity().setAgents(List.of(a));
        SecurityConfigValidator v = new SecurityConfigValidator(props);
        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hmac-secret");
    }

    @Test
    void validate_throws_whenAgentIdBlank() {
        MonitorProperties props = new MonitorProperties();
        props.getSecurity().setDashboardPassword("strong-password");
        props.getSecurity().setAgentApiKey("");
        MonitorProperties.AgentCredential a = new MonitorProperties.AgentCredential();
        a.setId("");
        a.setHmacSecret("0123456789abcdef0123456789abcdef");
        props.getSecurity().setAgents(List.of(a));
        SecurityConfigValidator v = new SecurityConfigValidator(props);
        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id");
    }
}
