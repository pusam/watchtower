package com.watchtower.alarm;

import com.watchtower.domain.AlarmEvent;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NotificationDispatcher {

    private final List<Notifier> notifiers;

    public NotificationDispatcher(List<Notifier> notifiers) {
        this.notifiers = notifiers;
        log.info("NotificationDispatcher registered {} notifier(s)", notifiers.size());
    }

    public void dispatch(AlarmEvent alarm) {
        for (Notifier n : notifiers) {
            try {
                n.send(alarm);
            } catch (Exception e) {
                log.warn("notifier {} failed", n.getClass().getSimpleName(), e);
            }
        }
    }
}
