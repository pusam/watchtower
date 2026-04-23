package com.watchtower.alarm;

import com.watchtower.config.MonitorProperties;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MaintenanceService {

    private final MonitorProperties properties;
    // Epoch ms: every host is muted until this instant. Independent from per-host mutes
    // so that adding a host-scoped mute on top of "mute all" does not narrow the scope.
    private volatile long allMuteUntil = 0L;
    private final Map<String, Long> hostMuteUntil = new ConcurrentHashMap<>();

    public MaintenanceService(MonitorProperties properties) {
        this.properties = properties;
    }

    public boolean isMuted(String hostId) {
        long now = System.currentTimeMillis();
        if (allMuteUntil > now) return true;
        Long until = hostMuteUntil.get(hostId);
        if (until != null && until > now) return true;
        Instant nowInstant = Instant.now();
        for (MonitorProperties.MaintenanceWindow w : properties.getMaintenance()) {
            if (!inWindow(w, nowInstant)) continue;
            if (w.getHostIds() == null || w.getHostIds().isEmpty()) return true;
            if (w.getHostIds().contains(hostId)) return true;
        }
        return false;
    }

    public void muteAllFor(long durationSec) {
        long target = System.currentTimeMillis() + durationSec * 1000L;
        allMuteUntil = Math.max(allMuteUntil, target);
        log.info("muted all hosts for {}s", durationSec);
    }

    public void muteHostFor(String hostId, long durationSec) {
        long target = System.currentTimeMillis() + durationSec * 1000L;
        hostMuteUntil.merge(hostId, target, Math::max);
        log.info("muted host {} for {}s", hostId, durationSec);
    }

    public void unmuteAll() {
        allMuteUntil = 0L;
        hostMuteUntil.clear();
    }

    private boolean inWindow(MonitorProperties.MaintenanceWindow w, Instant now) {
        try {
            Instant from = OffsetDateTime.parse(w.getFrom()).toInstant();
            Instant to = OffsetDateTime.parse(w.getTo()).toInstant();
            return !now.isBefore(from) && now.isBefore(to);
        } catch (DateTimeParseException e) {
            log.warn("invalid maintenance window times: from={} to={}", w.getFrom(), w.getTo());
            return false;
        }
    }
}
