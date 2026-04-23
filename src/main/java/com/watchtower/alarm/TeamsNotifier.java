package com.watchtower.alarm;

import com.watchtower.config.MonitorProperties;
import com.watchtower.domain.AlarmEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Posts a MessageCard to a Microsoft Teams incoming-webhook URL.
 * (Office 365 legacy connector format — widely supported across M365 tenants.)
 */
@Slf4j
@Component
public class TeamsNotifier implements Notifier {

    private final WebClient webClient = WebClient.builder().build();
    private final MonitorProperties properties;

    public TeamsNotifier(MonitorProperties properties) {
        this.properties = properties;
    }

    @Override
    public void send(AlarmEvent alarm) {
        String url = properties.getAlarms().getTeamsWebhookUrl();
        if (url == null || url.isBlank()) return;

        boolean resolved = alarm.state() == AlarmEvent.State.RESOLVED;
        String themeColor = resolved ? "2EB886"
                : switch (alarm.severity()) {
                    case CRIT -> "D50200";
                    case WARN -> "FFAA00";
                    default -> "6F7074";
                };
        String title = String.format("[%s] %s · %s",
                resolved ? "RESOLVED" : "FIRING",
                alarm.type(),
                alarm.hostName());

        Map<String, Object> body = Map.of(
                "@type", "MessageCard",
                "@context", "http://schema.org/extensions",
                "themeColor", themeColor,
                "summary", title,
                "title", title,
                "text", alarm.message(),
                "sections", List.of(Map.of("facts", List.of(
                        Map.of("name", "Severity", "value", alarm.severity().name()),
                        Map.of("name", "Host",     "value", alarm.hostName()),
                        Map.of("name", "Value",    "value", String.valueOf(alarm.value())),
                        Map.of("name", "Threshold","value", String.valueOf(alarm.threshold()))
                )))
        );

        try {
            webClient.post().uri(url)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(5))
                    .block();
        } catch (Exception e) {
            log.warn("Teams webhook failed", e);
        }
    }
}
