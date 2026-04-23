package com.watchtower.alarm;

import com.watchtower.analytics.EndpointStatsService;
import com.watchtower.config.MonitorProperties;
import com.watchtower.domain.AlarmEvent;
import com.watchtower.domain.HostSnapshot;
import com.watchtower.persistence.AlarmRepository;
import com.watchtower.store.MetricsStore;
import com.watchtower.websocket.MetricsPublisher;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AlarmEngine {

    private final MetricsStore store;
    private final MonitorProperties properties;
    private final MetricsPublisher publisher;
    private final NotificationDispatcher notifier;
    private final EndpointStatsService endpointStats;
    private final ObjectProvider<AlarmRepository> repoProvider;
    private final MaintenanceService maintenance;

    /** key = hostId + "|" + type (+ optional qualifier) -> current firing alarm */
    private final Map<String, AlarmEvent> active = new ConcurrentHashMap<>();
    /** last slack notify time per key (epoch ms) */
    private final Map<String, Long> lastNotified = new ConcurrentHashMap<>();
    /** first time a breach was seen (for duration-based firing) */
    private final Map<String, Long> pendingSince = new ConcurrentHashMap<>();
    /** recent alarm log (capped) */
    private final java.util.Deque<AlarmEvent> history = new java.util.ArrayDeque<>();
    private static final int HISTORY_MAX = 200;
    private final AnomalyBaseline anomalyBaseline;

    public AlarmEngine(MetricsStore store, MonitorProperties properties,
                       MetricsPublisher publisher, NotificationDispatcher notifier,
                       EndpointStatsService endpointStats,
                       ObjectProvider<AlarmRepository> repoProvider,
                       MaintenanceService maintenance) {
        this.store = store;
        this.properties = properties;
        this.publisher = publisher;
        this.notifier = notifier;
        this.endpointStats = endpointStats;
        this.repoProvider = repoProvider;
        this.maintenance = maintenance;
        this.anomalyBaseline = new AnomalyBaseline(properties.getAlarms().getAnomaly().getWindowSize());
    }

    @PostConstruct
    public void init() {
        log.info("AlarmEngine enabled={} interval={}ms",
                properties.getAlarms().isEnabled(),
                properties.getAlarms().getCheckIntervalMs());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (properties.getPersistence().isEnabled()) restoreFromDb();
    }

    private void restoreFromDb() {
        AlarmRepository repo = repoProvider.getIfAvailable();
        if (repo == null) return;
        try {
            for (AlarmRepository.ActiveRow row : repo.loadActiveWithQualifier()) {
                active.put(key(row.event().hostId(), row.event().type(), row.qualifier()), row.event());
            }
            List<AlarmEvent> recent = repo.loadRecent(HISTORY_MAX);
            synchronized (history) {
                for (int i = recent.size() - 1; i >= 0; i--) history.addLast(recent.get(i));
            }
            lastNotified.putAll(repo.loadLastNotified());
            log.info("restored {} active alarms, {} history, {} cooldown entries",
                    active.size(), recent.size(), lastNotified.size());
        } catch (Exception e) {
            log.warn("alarm restore failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${watchtower.alarms.check-interval-ms:10000}")
    public void evaluate() {
        if (!properties.getAlarms().isEnabled()) return;
        try {
            for (HostSnapshot snap : store.latestAll()) {
                evalHost(snap);
            }
        } catch (Exception e) {
            log.warn("alarm evaluation failed", e);
        }
    }

    private void evalHost(HostSnapshot snap) {
        MonitorProperties.Alarms cfg = properties.getAlarms();
        String hostId = snap.hostId();
        String hostName = snap.displayName() != null ? snap.displayName() : hostId;
        Instant now = Instant.now();

        long staleMs = snap.timestamp() == null
                ? Long.MAX_VALUE
                : (now.toEpochMilli() - snap.timestamp().toEpochMilli());
        if (snap.status() == HostSnapshot.Status.DOWN || staleMs > cfg.getHostDownThresholdMs()) {
            trigger(hostId, hostName, AlarmEvent.Type.HOST_DOWN, null, AlarmEvent.Severity.CRIT,
                    String.format("호스트 응답 없음 (마지막 수신 %d초 전)", staleMs / 1000),
                    staleMs / 1000.0, cfg.getHostDownThresholdMs() / 1000.0);
        } else {
            resolve(hostId, hostName, AlarmEvent.Type.HOST_DOWN, null);
        }

        HostSnapshot.HostInfo h = snap.host();
        if (h != null) {
            if (h.cpuUsedPct() >= cfg.getCpuThresholdPct()) {
                trigger(hostId, hostName, AlarmEvent.Type.CPU, null,
                        h.cpuUsedPct() >= 95 ? AlarmEvent.Severity.CRIT : AlarmEvent.Severity.WARN,
                        String.format("CPU 사용률 %.1f%%", h.cpuUsedPct()),
                        h.cpuUsedPct(), cfg.getCpuThresholdPct());
            } else {
                resolve(hostId, hostName, AlarmEvent.Type.CPU, null);
            }

            double memPct = h.memTotal() > 0 ? (h.memUsed() * 100.0) / h.memTotal() : 0;
            if (memPct >= cfg.getMemThresholdPct()) {
                trigger(hostId, hostName, AlarmEvent.Type.MEMORY, null,
                        memPct >= 95 ? AlarmEvent.Severity.CRIT : AlarmEvent.Severity.WARN,
                        String.format("메모리 사용률 %.1f%%", memPct),
                        memPct, cfg.getMemThresholdPct());
            } else {
                resolve(hostId, hostName, AlarmEvent.Type.MEMORY, null);
            }

            if (h.disks() != null) {
                for (HostSnapshot.DiskInfo d : h.disks()) {
                    if (d.total() <= 0) continue;
                    double pct = (d.used() * 100.0) / d.total();
                    String q = d.mount();
                    if (pct >= cfg.getDiskThresholdPct()) {
                        trigger(hostId, hostName, AlarmEvent.Type.DISK, q,
                                pct >= 95 ? AlarmEvent.Severity.CRIT : AlarmEvent.Severity.WARN,
                                String.format("디스크 %s 사용률 %.1f%%", d.mount(), pct),
                                pct, cfg.getDiskThresholdPct());
                    } else {
                        resolve(hostId, hostName, AlarmEvent.Type.DISK, q);
                    }
                }
            }
        }

        if (snap.certs() != null) {
            for (HostSnapshot.Cert c : snap.certs()) {
                String q = c.subject();
                if (c.daysLeft() >= 0 && c.daysLeft() <= cfg.getCertDaysLeftThreshold()) {
                    AlarmEvent.Severity sev = c.daysLeft() <= 3
                            ? AlarmEvent.Severity.CRIT
                            : c.daysLeft() <= 7 ? AlarmEvent.Severity.WARN : AlarmEvent.Severity.INFO;
                    trigger(hostId, hostName, AlarmEvent.Type.CERT_EXPIRING, q, sev,
                            String.format("인증서 %s %d일 후 만료", c.subject(), c.daysLeft()),
                            c.daysLeft(), cfg.getCertDaysLeftThreshold());
                } else {
                    resolve(hostId, hostName, AlarmEvent.Type.CERT_EXPIRING, q);
                }
            }
        }

        double errRate = endpointStats.errorRate(hostId, cfg.getErrorRateWindowSec());
        if (errRate >= cfg.getErrorRateThresholdPct()) {
            trigger(hostId, hostName, AlarmEvent.Type.ERROR_RATE, null,
                    errRate >= cfg.getErrorRateThresholdPct() * 3 ? AlarmEvent.Severity.CRIT : AlarmEvent.Severity.WARN,
                    String.format("5xx 에러율 %.1f%% (최근 %d초)", errRate, cfg.getErrorRateWindowSec()),
                    errRate, cfg.getErrorRateThresholdPct());
        } else {
            resolve(hostId, hostName, AlarmEvent.Type.ERROR_RATE, null);
        }

        evalAnomalies(snap, hostId, hostName, errRate);
    }

    /**
     * Feeds the rolling baseline and fires WARN ANOMALY when the current value is
     * {@code >= zThreshold} std-devs above the mean. Threshold-based alarms above
     * still fire independently; anomaly alarms catch the "unusual for this host"
     * band below those absolute thresholds.
     */
    private void evalAnomalies(HostSnapshot snap, String hostId, String hostName, double errRate) {
        MonitorProperties.Anomaly a = properties.getAlarms().getAnomaly();
        if (!a.isEnabled()) return;

        HostSnapshot.HostInfo h = snap.host();
        if (h != null) {
            double cpu = h.cpuUsedPct();
            anomalyBaseline.record(hostId, "cpu", cpu);
            checkAnomaly(hostId, hostName, "cpu", cpu, a.getMinCpuPct(), a, "CPU");

            double memPct = h.memTotal() > 0 ? (h.memUsed() * 100.0) / h.memTotal() : 0;
            anomalyBaseline.record(hostId, "mem", memPct);
            checkAnomaly(hostId, hostName, "mem", memPct, a.getMinMemPct(), a, "메모리");
        }

        anomalyBaseline.record(hostId, "errRate", errRate);
        checkAnomaly(hostId, hostName, "errRate", errRate, a.getMinErrorRatePct(), a, "에러율");
    }

    private void checkAnomaly(String hostId, String hostName, String metric, double value,
                              double minValue, MonitorProperties.Anomaly cfg, String label) {
        if (value < minValue) {
            resolve(hostId, hostName, AlarmEvent.Type.ANOMALY, metric);
            return;
        }
        double z = anomalyBaseline.zScore(hostId, metric, value, cfg.getMinSamples());
        if (Double.isNaN(z) || z < cfg.getZThreshold()) {
            resolve(hostId, hostName, AlarmEvent.Type.ANOMALY, metric);
            return;
        }
        trigger(hostId, hostName, AlarmEvent.Type.ANOMALY, metric, AlarmEvent.Severity.WARN,
                String.format("%s 이상 징후: 현재 %.1f (기준선 대비 z=%.1fσ)", label, value, z),
                value, cfg.getZThreshold());
    }

    private String key(String hostId, AlarmEvent.Type t, String q) {
        return hostId + "|" + t + "|" + (q == null ? "" : q);
    }

    private void trigger(String hostId, String hostName, AlarmEvent.Type type, String qualifier,
                         AlarmEvent.Severity sev, String msg, double value, double threshold) {
        String k = key(hostId, type, qualifier);
        AlarmEvent existing = active.get(k);
        if (existing != null) {
            AlarmEvent updated = new AlarmEvent(existing.id(), hostId, hostName, type, sev, msg,
                    value, threshold, existing.firedAt(), null, AlarmEvent.State.FIRING,
                    existing.acknowledged(), existing.acknowledgedAt(), existing.acknowledgedBy());
            active.put(k, updated);
            persist(updated, qualifier);
            publisher.publishAlarm(updated);
            maybeNotify(k, updated);
            return;
        }
        if (!breachHeldLongEnough(k, type, sev)) return;
        AlarmEvent ev = new AlarmEvent(UUID.randomUUID().toString(), hostId, hostName, type, sev,
                msg, value, threshold, Instant.now(), null, AlarmEvent.State.FIRING,
                false, null, null);
        active.put(k, ev);
        pendingSince.remove(k);
        appendHistory(ev);
        persist(ev, qualifier);
        publisher.publishAlarm(ev);
        maybeNotify(k, ev);
    }

    public boolean acknowledge(String alarmId, String user) {
        String effectiveUser = user == null ? "unknown" : user;
        Instant now = Instant.now();
        for (Map.Entry<String, AlarmEvent> entry : active.entrySet()) {
            if (!entry.getValue().id().equals(alarmId)) continue;
            String mapKey = entry.getKey();
            AlarmEvent[] result = new AlarmEvent[1];
            boolean[] changed = new boolean[1];
            active.computeIfPresent(mapKey, (k, cur) -> {
                if (!cur.id().equals(alarmId)) return cur;
                if (cur.acknowledged()) {
                    result[0] = cur;
                    return cur;
                }
                AlarmEvent acked = cur.acknowledge(now, effectiveUser);
                result[0] = acked;
                changed[0] = true;
                return acked;
            });
            AlarmEvent acked = result[0];
            if (acked == null) continue;
            if (!changed[0]) return true;
            appendHistory(acked);
            persist(acked, qualifierFromKey(mapKey));
            publisher.publishAlarm(acked);
            log.info("alarm {} acknowledged by {}", alarmId, effectiveUser);
            return true;
        }
        return false;
    }

    private String qualifierFromKey(String k) {
        int i = k.lastIndexOf('|');
        if (i < 0 || i == k.length() - 1) return null;
        String q = k.substring(i + 1);
        return q.isEmpty() ? null : q;
    }

    /**
     * Enforce duration-based firing: the condition must hold for `breachDurationSec`
     * before the alarm actually fires. CRIT/HOST_DOWN bypasses the delay.
     */
    private boolean breachHeldLongEnough(String k, AlarmEvent.Type type, AlarmEvent.Severity sev) {
        MonitorProperties.Alarms cfg = properties.getAlarms();
        Long override = cfg.getDurationOverridesSec() == null
                ? null : cfg.getDurationOverridesSec().get(type.name());
        long durationSec = override != null ? override : cfg.getBreachDurationSec();
        long durationMs = durationSec * 1000L;
        if (durationMs <= 0) return true;
        if (type == AlarmEvent.Type.HOST_DOWN || type == AlarmEvent.Type.ENDPOINT_DOWN
                || sev == AlarmEvent.Severity.CRIT) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long since = pendingSince.putIfAbsent(k, now);
        long first = since == null ? now : since;
        return (now - first) >= durationMs;
    }

    private void resolve(String hostId, String hostName, AlarmEvent.Type type, String qualifier) {
        String k = key(hostId, type, qualifier);
        pendingSince.remove(k);
        AlarmEvent existing = active.remove(k);
        if (existing == null) return;
        AlarmEvent resolved = existing.resolve(Instant.now());
        appendHistory(resolved);
        persist(resolved, qualifier);
        publisher.publishAlarm(resolved);
        if (!maintenance.isMuted(hostId)) notifier.dispatch(resolved);
        lastNotified.remove(k);
        AlarmRepository repo = repoProvider.getIfAvailable();
        if (repo != null) repo.deleteLastNotified(k);
    }

    private void maybeNotify(String k, AlarmEvent ev) {
        if (maintenance.isMuted(ev.hostId())) return;
        if (ev.acknowledged()) return;
        long now = System.currentTimeMillis();
        long cooldownMs = properties.getAlarms().getCooldownSeconds() * 1000L;
        Long last = lastNotified.get(k);
        if (last != null && now - last < cooldownMs) return;
        notifier.dispatch(ev);
        lastNotified.put(k, now);
        AlarmRepository repo = repoProvider.getIfAvailable();
        if (repo != null) repo.saveLastNotified(k, now);
    }

    private void persist(AlarmEvent ev, String qualifier) {
        if (!properties.getPersistence().isEnabled()) return;
        AlarmRepository repo = repoProvider.getIfAvailable();
        if (repo != null) repo.upsert(ev, qualifier);
    }

    private void appendHistory(AlarmEvent ev) {
        synchronized (history) {
            history.addLast(ev);
            while (history.size() > HISTORY_MAX) history.pollFirst();
        }
    }

    public void raiseExternal(String sourceId, String displayName, AlarmEvent.Type type, String qualifier,
                              AlarmEvent.Severity sev, String message, double value, double threshold) {
        trigger(sourceId, displayName, type, qualifier, sev, message, value, threshold);
    }

    public void clearExternal(String sourceId, String displayName, AlarmEvent.Type type, String qualifier) {
        resolve(sourceId, displayName, type, qualifier);
    }

    public List<AlarmEvent> activeAlarms() {
        List<AlarmEvent> list = new ArrayList<>(active.values());
        list.sort(Comparator.comparing(AlarmEvent::firedAt).reversed());
        return list;
    }

    public List<AlarmEvent> recentHistory() {
        synchronized (history) {
            List<AlarmEvent> list = new ArrayList<>(history);
            Collections.reverse(list);
            return list;
        }
    }
}
