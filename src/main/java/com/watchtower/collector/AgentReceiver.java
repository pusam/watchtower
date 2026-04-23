package com.watchtower.collector;

import com.watchtower.config.AgentSignatureFilter;
import com.watchtower.domain.HostSnapshot;
import com.watchtower.registry.HostRegistry;
import com.watchtower.store.MetricsStore;
import com.watchtower.websocket.MetricsPublisher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentReceiver {

    private final HostRegistry registry;
    private final MetricsStore store;
    private final MetricsPublisher publisher;

    @PostMapping("/report")
    public ResponseEntity<Void> report(@Valid @RequestBody AgentReport report,
                                       HttpServletRequest request) {
        String hostId = report.hostId();
        String displayName = report.displayName();
        String agentUrl = report.agentUrl();

        Object authedAgent = request.getAttribute(AgentSignatureFilter.AUTHENTICATED_AGENT_ATTR);
        if (authedAgent instanceof String authedId
                && !"legacy".equals(authedId)
                && !authedId.equals(hostId)) {
            log.warn("agent {} attempted to report as {}", authedId, hostId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (!registry.tryConsume(hostId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        registry.register(hostId, displayName, agentUrl);
        registry.markPushed(hostId);

        HostSnapshot snapshot = toSnapshot(report);
        store.put(snapshot);
        publisher.publish(snapshot);

        log.debug("push received from {}", hostId);
        return ResponseEntity.ok().build();
    }

    @SuppressWarnings("unchecked")
    private HostSnapshot toSnapshot(AgentReport report) {
        Map<String, Object> hostMap = report.host();
        List<Map<String, Object>> appList = report.apps() != null ? report.apps() : List.of();

        HostSnapshot.HostInfo host = hostMap == null ? null : new HostSnapshot.HostInfo(
                str(hostMap, "hostname"),
                str(hostMap, "osName"),
                str(hostMap, "kernelVersion"),
                intVal(hostMap, "cpuCores"),
                doubleVal(hostMap, "loadAvg1"),
                doubleVal(hostMap, "loadAvg5"),
                doubleVal(hostMap, "loadAvg15"),
                doubleVal(hostMap, "cpuUsedPct"),
                longVal(hostMap, "memTotal"),
                longVal(hostMap, "memUsed"),
                longVal(hostMap, "memFree"),
                longVal(hostMap, "memAvailable"),
                longVal(hostMap, "swapTotal"),
                longVal(hostMap, "swapUsed"),
                longVal(hostMap, "uptimeSeconds"),
                longVal(hostMap, "netRxBps"),
                longVal(hostMap, "netTxBps"),
                intVal(hostMap, "tcpEstablished"),
                ((List<Map<String, Object>>) hostMap.getOrDefault("listenPorts", List.of())).stream()
                        .map(p -> new HostSnapshot.ListenPort(
                                intVal(p, "port"), str(p, "proto"),
                                str(p, "process"), longVal(p, "pid")))
                        .toList(),
                ((List<Map<String, Object>>) hostMap.getOrDefault("disks", List.of())).stream()
                        .map(d -> new HostSnapshot.DiskInfo(
                                str(d, "mount"), str(d, "fsType"),
                                longVal(d, "total"), longVal(d, "used"), longVal(d, "usable")))
                        .toList()
        );

        List<HostSnapshot.AppProcess> apps = appList.stream()
                .map(a -> new HostSnapshot.AppProcess(
                        str(a, "name"),
                        longVal(a, "pid"),
                        Boolean.TRUE.equals(a.get("registered")),
                        str(a, "cmdline"),
                        longVal(a, "memRss"),
                        longVal(a, "uptimeSeconds")))
                .toList();

        List<Map<String, Object>> reqList = report.requests() != null ? report.requests() : List.of();
        List<HostSnapshot.RequestLog> requests = reqList.stream()
                .map(r -> new HostSnapshot.RequestLog(
                        longVal(r, "timestamp"),
                        str(r, "method"),
                        str(r, "path"),
                        intVal(r, "status"),
                        longVal(r, "elapsedMs"),
                        longVal(r, "bytes"),
                        str(r, "remoteIp"),
                        str(r, "source")))
                .toList();

        List<Map<String, Object>> certList = report.certs() != null ? report.certs() : List.of();
        List<HostSnapshot.Cert> certs = certList.stream()
                .map(c -> new HostSnapshot.Cert(
                        str(c, "subject"),
                        str(c, "issuer"),
                        longVal(c, "notAfter"),
                        longVal(c, "daysLeft"),
                        str(c, "sans"),
                        str(c, "source")))
                .toList();

        List<Map<String, Object>> sqList = report.slowQueries() != null ? report.slowQueries() : List.of();
        List<HostSnapshot.SlowQuery> slowQueries = sqList.stream()
                .map(s -> new HostSnapshot.SlowQuery(
                        longVal(s, "timestamp"),
                        longVal(s, "elapsedMs"),
                        longVal(s, "lockMs"),
                        longVal(s, "rowsSent"),
                        longVal(s, "rowsExamined"),
                        str(s, "database"),
                        str(s, "user"),
                        str(s, "clientHost"),
                        SqlRedactor.redact(str(s, "sql")),
                        str(s, "source")))
                .toList();

        return new HostSnapshot(
                report.hostId(),
                report.displayName() != null ? report.displayName() : report.hostId(),
                Instant.now(),
                HostSnapshot.Status.UP, null, host, apps, requests, certs, slowQueries);
    }

    public record AgentReport(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-zA-Z0-9._-]+$") String hostId,
            @Size(max = 128) String displayName,
            @Size(max = 256) String agentUrl,
            Map<String, Object> host,
            @Size(max = 200) List<Map<String, Object>> apps,
            @Size(max = 2000) List<Map<String, Object>> requests,
            @Size(max = 200) List<Map<String, Object>> certs,
            @Size(max = 500) List<Map<String, Object>> slowQueries
    ) {}

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }
    private static long longVal(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Number n)) return 0L;
        long raw = n.longValue();
        return raw < 0 ? 0L : raw;
    }
    private static int intVal(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Number n)) return 0;
        int raw = n.intValue();
        return raw < 0 ? 0 : raw;
    }
    private static double doubleVal(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Number n)) return 0.0;
        double raw = n.doubleValue();
        if (Double.isNaN(raw) || Double.isInfinite(raw)) return 0.0;
        if ("cpuUsedPct".equals(k) || "cpuPct".equals(k)) {
            if (raw < 0) return 0.0;
            if (raw > 100.0) return 100.0;
        } else if (raw < 0) {
            return 0.0;
        }
        return raw;
    }
}
