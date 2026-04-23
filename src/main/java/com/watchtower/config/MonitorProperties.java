package com.watchtower.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private List<Probe> probes = new ArrayList<>();
    private List<MaintenanceWindow> maintenance = new ArrayList<>();
    private List<LogVolume> logVolumes = new ArrayList<>();
    private Security security = new Security();
    private Alarms alarms = new Alarms();
    private Persistence persistence = new Persistence();

    @Data
    public static class HostTarget {
        private String id;
        private String name;
        private String agentUrl;
    }

    @Data
    public static class Security {
        private String dashboardUsername = "admin";
        private String dashboardPassword = "";
        private String agentApiKey = "";
        private List<String> allowedOrigins = List.of("http://localhost:9090");
        private int maxRegistrationsPerMinute = 60;
        private List<DashboardUser> users = new ArrayList<>();
        private List<AgentCredential> agents = new ArrayList<>();
        private long agentMaxClockSkewSeconds = 300;
        private boolean allowLegacyApiKey = true;
        /**
         * Bearer token required on {@code /actuator/prometheus}. Empty = endpoint is disabled
         * (returns 403). Generate with {@code openssl rand -hex 32}.
         */
        private String prometheusScrapeToken = "";
    }

    @Data
    public static class AgentCredential {
        private String id;
        private String hmacSecret;
        // Additional secrets accepted during rotation (previous keys).
        // Remove entries after agents have migrated to the new primary secret.
        private List<String> previousHmacSecrets = new ArrayList<>();
    }

    @Data
    public static class DashboardUser {
        private String username;
        private String password;
        private String role = "VIEWER";
    }

    @Data
    public static class MaintenanceWindow {
        private String name;
        private String from;
        private String to;
        private List<String> hostIds = new ArrayList<>();
    }

    @Data
    public static class LogVolume {
        private String id;
        private String name;
        private String path;
        private long maxBytes;
    }

    @Data
    public static class Probe {
        private String id;
        private String name;
        private String type = "http";
        private String target;
        private int expectedStatus = 200;
        private long timeoutMs = 3000;
        private long intervalMs = 30000;
        private long slowThresholdMs = 1500;
    }

    @Data
    public static class Persistence {
        private boolean enabled = true;
        private String dbPath = "./data/watchtower.db";
        private int snapshotRetentionDays = 7;
        private int alarmRetentionDays = 30;
        private long writeIntervalMs = 0;
        private int restoreSnapshotsPerHost = 60;
        // If set, overrides db-path and connects to the given JDBC URL (e.g. PostgreSQL).
        private String jdbcUrl = "";
        private String username = "";
        private String password = "";
        private int poolSize = 8;
    }

    @Data
    public static class Alarms {
        private boolean enabled = true;
        private long checkIntervalMs = 10000;
        private long cooldownSeconds = 300;
        private long breachDurationSec = 60;
        private Map<String, Long> durationOverridesSec = new HashMap<>();
        private String slackWebhookUrl = "";
        private String discordWebhookUrl = "";
        private String genericWebhookUrl = "";
        private String teamsWebhookUrl = "";
        /** PagerDuty Events API v2 integration ("routing key"). */
        private String pagerdutyRoutingKey = "";
        /** Opsgenie REST API key. */
        private String opsgenieApiKey = "";
        /** Defaults to US region; set {@code https://api.eu.opsgenie.com} for EU tenants. */
        private String opsgenieApiBaseUrl = "https://api.opsgenie.com";
        private double cpuThresholdPct = 90;
        private double memThresholdPct = 90;
        private double diskThresholdPct = 85;
        private double logVolumeThresholdPct = 80;
        private long logVolumeCheckIntervalMs = 60_000L;
        private double errorRateThresholdPct = 5;
        private long errorRateWindowSec = 60;
        private long slowResponseMs = 3000;
        private long certDaysLeftThreshold = 14;
        private long hostDownThresholdMs = 30000;
    }
}
