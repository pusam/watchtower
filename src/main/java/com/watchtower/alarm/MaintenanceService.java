package com.watchtower.alarm;

import com.watchtower.config.MonitorProperties;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MaintenanceService {

    private final MonitorProperties properties;
    private final Set<String> runtimeMutedHosts = ConcurrentHashMap.newKeySet();
    private volatile long runtimeMuteUntil = 0L;

    public MaintenanceService(MonitorProperties properties) {
        this.properties = properties;
    }

    public boolean isMuted(String hostId) {
        if (runtimeMuteUntil > System.currentTimeMillis()) {
            if (runtimeMutedHosts.isEmpty() || runtimeMutedHosts.contains(hostId)) {
                return true;
            }
        }
        Instant now = Instant.now();
        for (MonitorProperties.MaintenanceWindow w : properties.getMaintenance()) {
            if (!inWindow(w, now)) continue;
            if (w.getHostIds() == null || w.getHostIds().isEmpty()) return true;
            if (w.getHostIds().contains(hostId)) return true;
        }
        return false;
    }

    public void muteAllFor(long durationSec) {
        runtimeMutedHosts.clear();
        runtimeMuteUntil = System.currentTimeMillis() + durationSec * 1000L;
        log.info("muted all hosts for {}s", durationSec);
    }

    public void muteHostFor(String hostId, long durationSec) {
        runtimeMutedHosts.add(hostId);
        runtimeMuteUntil = Math.max(runtimeMuteUntil, System.currentTimeMillis() + durationSec * 1000L);
        log.info("muted host {} for {}s", hostId, durationSec);
    }

    public void unmuteAll() {
        runtimeMutedHosts.clear();
        runtimeMuteUntil = 0L;
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
