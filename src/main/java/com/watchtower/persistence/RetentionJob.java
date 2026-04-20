package com.watchtower.persistence;

import com.watchtower.config.MonitorProperties;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RetentionJob {

    private final MonitorProperties properties;
    private final SnapshotRepository snapshots;
    private final AlarmRepository alarms;
    private final ProbeResultRepository probeResults;

    public RetentionJob(MonitorProperties properties,
                        SnapshotRepository snapshots,
                        AlarmRepository alarms,
                        ProbeResultRepository probeResults) {
        this.properties = properties;
        this.snapshots = snapshots;
        this.alarms = alarms;
        this.probeResults = probeResults;
    }

    @Scheduled(cron = "0 15 * * * *")
    public void prune() {
        if (!properties.getPersistence().isEnabled()) return;
        long now = System.currentTimeMillis();
        try {
            int snapDays = properties.getPersistence().getSnapshotRetentionDays();
            int alarmDays = properties.getPersistence().getAlarmRetentionDays();
            int snapDeleted = snapshots.deleteOlderThan(now - Duration.ofDays(snapDays).toMillis());
            int alarmDeleted = alarms.deleteOlderThan(now - Duration.ofDays(alarmDays).toMillis());
            int probeDeleted = probeResults.deleteOlderThan(now - Duration.ofDays(snapDays).toMillis());
            if (snapDeleted > 0 || alarmDeleted > 0 || probeDeleted > 0) {
                log.info("retention: pruned {} snapshots, {} alarms, {} probe results",
                        snapDeleted, alarmDeleted, probeDeleted);
            }
        } catch (Exception e) {
            log.warn("retention pruning failed: {}", e.getMessage());
        }
    }
}
