package com.watchtower.alarm;

import com.watchtower.analytics.EndpointStatsService;
import com.watchtower.config.MonitorProperties;
import com.watchtower.domain.AlarmEvent;
import com.watchtower.domain.HostSnapshot;
import com.watchtower.store.MetricsStore;
import com.watchtower.websocket.MetricsPublisher;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AlarmEngine {

    private final MetricsStore store;
    private final MonitorProperties properties;
    private final MetricsPublisher publisher;
    private final SlackNotifier slack;
    private final EndpointStatsService endpointStats;

    /** key = hostId + "|" + type (+ optional qualifier) -> current firing alarm */
    private final Map<String, AlarmEvent> active = new ConcurrentHashMap<>();
    /** last slack notify time per key (epoch ms) */
    private final Map<String, Long> lastNotified = new ConcurrentHashMap<>();
    /** recent alarm log (capped) */
    private final java.util.Deque<AlarmEvent> history = new java.util.ArrayDeque<>();
    private static final int HISTORY_MAX = 200;

    public AlarmEngine(MetricsStore store, MonitorProperties properties,
                       MetricsPublisher publisher, SlackNotifier slack,
                       EndpointStatsService endpointStats) {
        this.store = store;
        this.properties = properties;
        this.publisher = publisher;
        this.slack = slack;
        this.endpointStats = endpointStats;
    }

    @PostConstruct
    public void init() {
        log.info("AlarmEngine enabled={} interval={}ms slack={}",
                properties.getAlarms().isEnabled(),
                properties.getAlarms().getCheckIntervalMs(),
                !properties.getAlarms().getSlackWebhookUrl().isBlank());
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
                    value, threshold, existing.firedAt(), null, AlarmEvent.State.FIRING);
            active.put(k, updated);
            publisher.publishAlarm(updated);
            maybeNotify(k, updated);
            return;
        }
        AlarmEvent ev = new AlarmEvent(UUID.randomUUID().toString(), hostId, hostName, type, sev,
                msg, value, threshold, Instant.now(), null, AlarmEvent.State.FIRING);
        active.put(k, ev);
        appendHistory(ev);
        publisher.publishAlarm(ev);
        maybeNotify(k, ev);
    }

    private void resolve(String hostId, String hostName, AlarmEvent.Type type, String qualifier) {
        String k = key(hostId, type, qualifier);
        AlarmEvent existing = active.remove(k);
        if (existing == null) return;
        AlarmEvent resolved = existing.resolve(Instant.now());
        appendHistory(resolved);
        publisher.publishAlarm(resolved);
        slack.send(resolved);
        lastNotified.remove(k);
    }

    private void maybeNotify(String k, AlarmEvent ev) {
        long now = System.currentTimeMillis();
        long cooldownMs = properties.getAlarms().getCooldownSeconds() * 1000L;
        Long last = lastNotified.get(k);
        if (last != null && now - last < cooldownMs) return;
        slack.send(ev);
        lastNotified.put(k, now);
    }

    private void appendHistory(AlarmEvent ev) {
        synchronized (history) {
            history.addLast(ev);
            while (history.size() > HISTORY_MAX) history.pollFirst();
        }
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
