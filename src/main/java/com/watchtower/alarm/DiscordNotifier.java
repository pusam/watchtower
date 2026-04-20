package com.watchtower.alarm;

import com.watchtower.config.MonitorProperties;
import com.watchtower.domain.AlarmEvent;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Component
public class DiscordNotifier implements Notifier {

    private final WebClient webClient = WebClient.builder().build();
    private final MonitorProperties properties;

    public DiscordNotifier(MonitorProperties properties) {
        this.properties = properties;
    }

    @Override
    public void send(AlarmEvent alarm) {
        String url = properties.getAlarms().getDiscordWebhookUrl();
        if (url == null || url.isBlank()) return;

        String emoji = switch (alarm.severity()) {
            case CRIT -> "\uD83D\uDEA8";
            case WARN -> "\u26A0\uFE0F";
            default -> "\u2139\uFE0F";
        };
        String verb = alarm.state() == AlarmEvent.State.RESOLVED ? "RESOLVED \u2705" : "FIRING";
        String text = String.format("%s **[%s]** %s \u00B7 `%s` \u00B7 %s",
                emoji, verb, alarm.type(), alarm.hostName(), alarm.message());

        Map<String, Object> body = Map.of("content", text);
        try {
            webClient.post().uri(url)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(5))
                    .block();
        } catch (Exception e) {
            log.warn("Discord webhook failed: {}", e.getMessage());
        }
    }
}
