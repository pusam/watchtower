package com.watchtower.alarm;

import com.watchtower.config.MonitorProperties;
import com.watchtower.domain.AlarmEvent;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Sends alerts to PagerDuty via Events API v2. Uses the alarm id as {@code dedup_key}
 * so resolves automatically close the incident PagerDuty opened for the firing event.
 */
@Slf4j
@Component
public class PagerDutyNotifier implements Notifier {

    private static final String ENDPOINT = "https://events.pagerduty.com/v2/enqueue";

    private final WebClient webClient = WebClient.builder().build();
    private final MonitorProperties properties;

    public PagerDutyNotifier(MonitorProperties properties) {
        this.properties = properties;
    }

    @Override
    public void send(AlarmEvent alarm) {
        String routingKey = properties.getAlarms().getPagerdutyRoutingKey();
        if (routingKey == null || routingKey.isBlank()) return;

        boolean resolved = alarm.state() == AlarmEvent.State.RESOLVED;
        Map<String, Object> payload = new HashMap<>();
        payload.put("routing_key", routingKey);
        payload.put("event_action", resolved ? "resolve" : "trigger");
        payload.put("dedup_key", alarm.id());

        if (!resolved) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("summary", String.format("[%s] %s · %s · %s",
                    alarm.severity(), alarm.type(), alarm.hostName(), alarm.message()));
            detail.put("severity", mapSeverity(alarm.severity()));
            detail.put("source", alarm.hostId());
            detail.put("component", alarm.type().name());
            detail.put("custom_details", Map.of(
                    "value", String.valueOf(alarm.value()),
                    "threshold", String.valueOf(alarm.threshold()),
                    "host_name", alarm.hostName()));
            payload.put("payload", detail);
        }

        try {
            webClient.post().uri(ENDPOINT)
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(5))
                    .block();
        } catch (Exception e) {
            log.warn("PagerDuty event failed", e);
        }
    }

    private static String mapSeverity(AlarmEvent.Severity s) {
        return switch (s) {
            case CRIT -> "critical";
            case WARN -> "warning";
            default   -> "info";
        };
    }
}
