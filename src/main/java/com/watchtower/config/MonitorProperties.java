package com.watchtower.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "watchtower")
public class MonitorProperties {
    private long pollIntervalMs = 5000;
    private int historySize = 60;
    private long requestTimeoutMs = 3000;
    private long pushStaleThresholdMs = 15000;
    private List<HostTarget> hosts = new ArrayList<>();
    private Security security = new Security();
    private Alarms alarms = new Alarms();

    @Data
    public static class HostTarget {
        private String id;
        private String name;
        private String agentUrl;
    }

    @Data
    public static class Security {
        private String dashboardUsername = "admin";
        private String dashboardPassword = "changeme";
        private String agentApiKey = "";
        private List<String> allowedOrigins = List.of("http://localhost:9090");
        private int maxRegistrationsPerMinute = 12;
    }

    @Data
    public static class Alarms {
        private boolean enabled = true;
        private long checkIntervalMs = 10000;
        private long cooldownSeconds = 300;
        private String slackWebhookUrl = "";
        private double cpuThresholdPct = 90;
        private double memThresholdPct = 90;
        private double diskThresholdPct = 85;
        private double errorRateThresholdPct = 5;
        private long errorRateWindowSec = 60;
        private long slowResponseMs = 3000;
        private long certDaysLeftThreshold = 14;
        private long hostDownThresholdMs = 30000;
    }
}
