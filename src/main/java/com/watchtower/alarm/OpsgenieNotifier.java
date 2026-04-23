package com.watchtower.alarm;

import com.watchtower.config.MonitorProperties;
import com.watchtower.domain.AlarmEvent;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Opsgenie REST API integration. Uses the alarm id as {@code alias} so resolve events
 * close the same alert Opsgenie opened for the firing event.
 */
@Slf4j
@Component
public class OpsgenieNotifier implements Notifier {

    private final WebClient webClient = WebClient.builder().build();
    private final MonitorProperties properties;

    public OpsgenieNotifier(MonitorProperties properties) {
        this.properties = properties;
    }

    @Override
    public void send(AlarmEvent alarm) {
        String apiKey = properties.getAlarms().getOpsgenieApiKey();
        if (apiKey == null || apiKey.isBlank()) return;

        String base = properties.getAlarms().getOpsgenieApiBaseUrl();
        if (base == null || base.isBlank()) base = "https://api.opsgenie.com";
        String authz = "GenieKey " + apiKey;

        try {
            if (alarm.state() == AlarmEvent.State.RESOLVED) {
                String url = UriComponentsBuilder.fromHttpUrl(base)
                        .path("/v2/alerts/{alias}/close")
                        .queryParam("identifierType", "alias")
                        .buildAndExpand(alarm.id())
                        .toUriString();
                webClient.post().uri(url)
                        .header(HttpHeaders.AUTHORIZATION, authz)
                        .bodyValue(Map.of("source", "watchtower"))
                        .retrieve()
                        .toBodilessEntity()
                        .timeout(Duration.ofSeconds(5))
                        .block();
            } else {
                Map<String, Object> body = new HashMap<>();
                body.put("alias", alarm.id());
                body.put("message", String.format("[%s] %s · %s",
                        alarm.type(), alarm.hostName(), alarm.message()));
                body.put("description", String.format("value=%s threshold=%s",
                        alarm.value(), alarm.threshold()));
                body.put("priority", mapPriority(alarm.severity()));
                body.put("source", "watchtower");
                body.put("entity", alarm.hostId());
                body.put("tags", java.util.List.of(alarm.type().name().toLowerCase(), alarm.hostId()));
                webClient.post().uri(base + "/v2/alerts")
                        .header(HttpHeaders.AUTHORIZATION, authz)
                        .bodyValue(body)
                        .retrieve()
                        .toBodilessEntity()
                        .timeout(Duration.ofSeconds(5))
                        .block();
            }
        } catch (Exception e) {
            log.warn("Opsgenie request failed", e);
        }
    }

    private static String mapPriority(AlarmEvent.Severity s) {
        return switch (s) {
            case CRIT -> "P1";
            case WARN -> "P3";
            default   -> "P5";
        };
    }
}
