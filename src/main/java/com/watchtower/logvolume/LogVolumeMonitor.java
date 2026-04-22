package com.watchtower.logvolume;

import com.watchtower.alarm.AlarmEngine;
import com.watchtower.config.MonitorProperties;
import com.watchtower.domain.AlarmEvent;
import com.watchtower.domain.LogVolumeStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scans configured log directories, compares usage against per-volume size budget,
 * and raises/clears LOG_VOLUME alarms through AlarmEngine.
 */
@Slf4j
@Component
public class LogVolumeMonitor {

    static final String HOST_ID = "watchtower-local";
    static final String HOST_NAME = "Watchtower";

    private final MonitorProperties properties;
    private final AlarmEngine alarmEngine;
    private final SimpMessagingTemplate messagingTemplate;

    private final Map<String, LogVolumeStatus> status = new ConcurrentHashMap<>();

    @Autowired
    public LogVolumeMonitor(MonitorProperties properties,
                            AlarmEngine alarmEngine,
                            SimpMessagingTemplate messagingTemplate) {
        this.properties = properties;
        this.alarmEngine = alarmEngine;
        this.messagingTemplate = messagingTemplate;
    }

    @Scheduled(fixedDelayString = "${watchtower.alarms.log-volume-check-interval-ms:60000}",
               initialDelay = 15_000L)
    public void scan() {
        List<MonitorProperties.LogVolume> volumes = properties.getLogVolumes();
        if (volumes == null || volumes.isEmpty()) {
            if (!status.isEmpty()) status.clear();
            return;
        }
        double thresholdPct = properties.getAlarms().getLogVolumeThresholdPct();
        Set<String> keep = new HashSet<>();

        for (MonitorProperties.LogVolume v : volumes) {
            if (v.getId() == null || v.getId().isBlank()) continue;
            if (v.getPath() == null || v.getPath().isBlank()) continue;
            keep.add(v.getId());

            LogVolumeStatus next;
            try {
                long size = computeSize(Paths.get(v.getPath()));
                double pct = v.getMaxBytes() > 0 ? (size * 100.0) / v.getMaxBytes() : 0.0;
                next = new LogVolumeStatus(v.getId(), displayName(v), v.getPath(),
                        v.getMaxBytes(), size, pct, Instant.now(), null);

                if (v.getMaxBytes() > 0 && pct >= thresholdPct) {
                    AlarmEvent.Severity sev = pct >= 95 ? AlarmEvent.Severity.CRIT : AlarmEvent.Severity.WARN;
                    String msg = String.format("로그 볼륨 %s 사용률 %.1f%% (%s / %s)",
                            displayName(v), pct, humanBytes(size), humanBytes(v.getMaxBytes()));
                    alarmEngine.raiseExternal(HOST_ID, HOST_NAME, AlarmEvent.Type.LOG_VOLUME, v.getId(),
                            sev, msg, pct, thresholdPct);
                } else {
                    alarmEngine.clearExternal(HOST_ID, HOST_NAME, AlarmEvent.Type.LOG_VOLUME, v.getId());
                }
            } catch (Exception e) {
                log.warn("log volume scan failed id={} path={}", v.getId(), v.getPath(), e);
                next = new LogVolumeStatus(v.getId(), displayName(v), v.getPath(),
                        v.getMaxBytes(), 0L, 0.0, Instant.now(), e.getMessage());
            }
            status.put(v.getId(), next);
        }

        status.keySet().removeIf(k -> !keep.contains(k));
        messagingTemplate.convertAndSend("/topic/log-volumes", snapshot());
    }

    public List<LogVolumeStatus> snapshot() {
        List<LogVolumeStatus> list = new ArrayList<>(status.values());
        list.sort(Comparator.comparingDouble(LogVolumeStatus::usedPct).reversed());
        return list;
    }

    static long computeSize(Path root) throws IOException {
        if (!Files.exists(root)) return 0L;
        if (Files.isRegularFile(root)) return Files.size(root);
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); } catch (IOException e) { return 0L; }
                    })
                    .sum();
        }
    }

    static String humanBytes(long b) {
        if (b <= 0) return "0 B";
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        double v = b;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(v < 10 ? "%.2f %s" : "%.1f %s", v, u[i]);
    }

    private static String displayName(MonitorProperties.LogVolume v) {
        return v.getName() == null || v.getName().isBlank() ? v.getId() : v.getName();
    }
}
