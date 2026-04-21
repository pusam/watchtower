package com.watchtower.config;

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
    void validate_throws_whenAgentApiKeyBlank() {
        MonitorProperties props = new MonitorProperties();
        props.getSecurity().setDashboardPassword("strong-password");
        props.getSecurity().setAgentApiKey("");
        SecurityConfigValidator v = new SecurityConfigValidator(props);
        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent-api-key");
    }

    @Test
    void validate_passes_whenBothSet() {
        MonitorProperties props = new MonitorProperties();
        props.getSecurity().setDashboardPassword("strong-password");
        props.getSecurity().setAgentApiKey("long-enough-agent-api-key-123456");
        SecurityConfigValidator v = new SecurityConfigValidator(props);
        // no throw
        v.validate();
        assertThat(props.getSecurity().getDashboardPassword()).isNotBlank();
    }
}
