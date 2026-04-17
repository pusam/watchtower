package com.watchtower.websocket;

import com.watchtower.domain.AlarmEvent;
import com.watchtower.domain.HostSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MetricsPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public void publish(HostSnapshot snapshot) {
        messagingTemplate.convertAndSend("/topic/metrics", snapshot);
    }

    public void publishAlarm(AlarmEvent alarm) {
        messagingTemplate.convertAndSend("/topic/alarms", alarm);
    }
}
