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
public class SlackNotifier {

    private final WebClient webClient = WebClient.builder().build();
    private final MonitorProperties properties;

    public SlackNotifier(MonitorProperties properties) {
        this.properties = properties;
    }

    public void send(AlarmEvent alarm) {
        String url = properties.getAlarms().getSlackWebhookUrl();
        if (url == null || url.isBlank()) return;

        String emoji = switch (alarm.severity()) {
            case CRIT -> ":rotating_light:";
            case WARN -> ":warning:";
            default -> ":information_source:";
        };
        String verb = alarm.state() == AlarmEvent.State.RESOLVED ? "RESOLVED :white_check_mark:" : "FIRING";
        String text = String.format("%s *[%s]* %s · `%s` · %s",
                emoji, verb, alarm.type(), alarm.hostName(), alarm.message());

        Map<String, Object> body = Map.of("text", text);
        try {
            webClient.post().uri(url)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(5))
                    .block();
        } catch (Exception e) {
            log.warn("Slack webhook failed: {}", e.getMessage());
        }
    }
}
