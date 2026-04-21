package com.watchtower.probe;

import com.watchtower.alarm.AlarmEngine;
import com.watchtower.config.MonitorProperties;
import com.watchtower.domain.AlarmEvent;
import com.watchtower.domain.ProbeResult;
import com.watchtower.persistence.ProbeResultRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SyntheticProbeService {

    private final MonitorProperties properties;
    private final AlarmEngine alarmEngine;
    private final ObjectProvider<ProbeResultRepository> repoProvider;
    private final Map<String, ProbeResult> lastResults = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "wt-probe");
                t.setDaemon(true);
                return t;
            });
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public SyntheticProbeService(MonitorProperties properties, AlarmEngine alarmEngine,
                                 ObjectProvider<ProbeResultRepository> repoProvider) {
        this.properties = properties;
        this.alarmEngine = alarmEngine;
        this.repoProvider = repoProvider;
    }

    @PostConstruct
    public void start() {
        List<MonitorProperties.Probe> probes = properties.getProbes();
        if (probes == null || probes.isEmpty()) {
            log.info("no synthetic probes configured");
            return;
        }
        for (MonitorProperties.Probe p : probes) {
            if (p.getId() == null || p.getId().isBlank() || p.getTarget() == null || p.getTarget().isBlank()) {
                log.warn("skipping invalid probe {}", p);
                continue;
            }
            long interval = Math.max(5000, p.getIntervalMs());
            executor.scheduleAtFixedRate(() -> runProbeSafe(p), 1000, interval, TimeUnit.MILLISECONDS);
            log.info("scheduled probe {} -> {} every {}ms", p.getId(), p.getTarget(), interval);
        }
    }

    private void runProbeSafe(MonitorProperties.Probe p) {
        try {
            ProbeResult result = execute(p);
            lastResults.put(p.getId(), result);
            persist(result);
            evaluateAlarms(p, result);
        } catch (Exception e) {
            log.warn("probe {} failed", p.getId(), e);
        }
    }

    private void persist(ProbeResult r) {
        if (!properties.getPersistence().isEnabled()) return;
        ProbeResultRepository repo = repoProvider.getIfAvailable();
        if (repo != null) repo.save(r);
    }

    private ProbeResult execute(MonitorProperties.Probe p) {
        String type = p.getType() == null ? "http" : p.getType().toLowerCase();
        Instant start = Instant.now();
        return switch (type) {
            case "tcp" -> probeTcp(p, start);
            default -> probeHttp(p, start);
        };
    }

    private ProbeResult probeHttp(MonitorProperties.Probe p, Instant start) {
        String displayName = p.getName() != null ? p.getName() : p.getId();
        long t0 = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(p.getTarget()))
                    .timeout(Duration.ofMillis(p.getTimeoutMs()))
                    .GET()
                    .build();
            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            long elapsed = (System.nanoTime() - t0) / 1_000_000L;
            boolean statusOk = resp.statusCode() == p.getExpectedStatus();
            ProbeResult.Status status;
            String err = null;
            if (!statusOk) {
                status = ProbeResult.Status.DOWN;
                err = "unexpected status " + resp.statusCode();
            } else if (elapsed > p.getSlowThresholdMs()) {
                status = ProbeResult.Status.SLOW;
            } else {
                status = ProbeResult.Status.UP;
            }
            return new ProbeResult(p.getId(), displayName, "http", p.getTarget(),
                    status, resp.statusCode(), elapsed, start, err);
        } catch (Exception e) {
            long elapsed = (System.nanoTime() - t0) / 1_000_000L;
            return new ProbeResult(p.getId(), displayName, "http", p.getTarget(),
                    ProbeResult.Status.DOWN, 0, elapsed, start, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private ProbeResult probeTcp(MonitorProperties.Probe p, Instant start) {
        String displayName = p.getName() != null ? p.getName() : p.getId();
        String target = p.getTarget();
        int colon = target.lastIndexOf(':');
        if (colon < 0) {
            return new ProbeResult(p.getId(), displayName, "tcp", target,
                    ProbeResult.Status.DOWN, 0, 0, start, "invalid target (expected host:port)");
        }
        String host = target.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(target.substring(colon + 1));
        } catch (NumberFormatException e) {
            return new ProbeResult(p.getId(), displayName, "tcp", target,
                    ProbeResult.Status.DOWN, 0, 0, start, "invalid port");
        }
        long t0 = System.nanoTime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), (int) p.getTimeoutMs());
            long elapsed = (System.nanoTime() - t0) / 1_000_000L;
            ProbeResult.Status status = elapsed > p.getSlowThresholdMs()
                    ? ProbeResult.Status.SLOW : ProbeResult.Status.UP;
            return new ProbeResult(p.getId(), displayName, "tcp", target,
                    status, 0, elapsed, start, null);
        } catch (Exception e) {
            long elapsed = (System.nanoTime() - t0) / 1_000_000L;
            return new ProbeResult(p.getId(), displayName, "tcp", target,
                    ProbeResult.Status.DOWN, 0, elapsed, start,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void evaluateAlarms(MonitorProperties.Probe p, ProbeResult r) {
        String sourceId = "probe:" + p.getId();
        String displayName = r.name();
        if (r.status() == ProbeResult.Status.DOWN) {
            alarmEngine.raiseExternal(sourceId, displayName,
                    AlarmEvent.Type.ENDPOINT_DOWN, null,
                    AlarmEvent.Severity.CRIT,
                    String.format("엔드포인트 %s 응답 실패 (%s)", r.target(),
                            r.error() != null ? r.error() : "status " + r.statusCode()),
                    r.statusCode(), p.getExpectedStatus());
            alarmEngine.clearExternal(sourceId, displayName, AlarmEvent.Type.ENDPOINT_SLOW, null);
        } else if (r.status() == ProbeResult.Status.SLOW) {
            alarmEngine.clearExternal(sourceId, displayName, AlarmEvent.Type.ENDPOINT_DOWN, null);
            alarmEngine.raiseExternal(sourceId, displayName,
                    AlarmEvent.Type.ENDPOINT_SLOW, null,
                    AlarmEvent.Severity.WARN,
                    String.format("엔드포인트 %s 응답 지연 %dms", r.target(), r.elapsedMs()),
                    r.elapsedMs(), p.getSlowThresholdMs());
        } else {
            alarmEngine.clearExternal(sourceId, displayName, AlarmEvent.Type.ENDPOINT_DOWN, null);
            alarmEngine.clearExternal(sourceId, displayName, AlarmEvent.Type.ENDPOINT_SLOW, null);
        }
    }

    public List<ProbeResult> currentResults() {
        List<ProbeResult> all = new ArrayList<>(lastResults.values());
        all.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return all;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
