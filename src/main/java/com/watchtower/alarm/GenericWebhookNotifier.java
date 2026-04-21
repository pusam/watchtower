package com.watchtower.alarm;

import com.watchtower.config.MonitorProperties;
import com.watchtower.domain.AlarmEvent;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Component
public class GenericWebhookNotifier implements Notifier {

    private final WebClient webClient = WebClient.builder().build();
    private final MonitorProperties properties;

    public GenericWebhookNotifier(MonitorProperties properties) {
        this.properties = properties;
    }

    @Override
    public void send(AlarmEvent alarm) {
        String url = properties.getAlarms().getGenericWebhookUrl();
        if (url == null || url.isBlank()) return;

        Map<String, Object> body = new HashMap<>();
        body.put("id", alarm.id());
        body.put("hostId", alarm.hostId());
        body.put("hostName", alarm.hostName());
        body.put("type", alarm.type().name());
        body.put("severity", alarm.severity().name());
        body.put("state", alarm.state().name());
        body.put("message", alarm.message());
        body.put("value", alarm.value());
        body.put("threshold", alarm.threshold());
        body.put("firedAt", alarm.firedAt() == null ? null : alarm.firedAt().toEpochMilli());
        body.put("resolvedAt", alarm.resolvedAt() == null ? null : alarm.resolvedAt().toEpochMilli());
        body.put("acknowledged", alarm.acknowledged());
        body.put("acknowledgedBy", alarm.acknowledgedBy());

        try {
            webClient.post().uri(url)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(5))
                    .block();
        } catch (Exception e) {
            log.warn("Generic webhook failed", e);
        }
    }
}
