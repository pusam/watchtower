package com.watchtower.collector;

import com.watchtower.config.MonitorProperties;
import com.watchtower.domain.HostSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class AgentClient {

    private final WebClient webClient;
    private final Duration timeout;

    public AgentClient(MonitorProperties properties) {
        this.webClient = WebClient.builder().build();
        this.timeout = Duration.ofMillis(properties.getRequestTimeoutMs());
    }

    @SuppressWarnings("unchecked")
    public HostSnapshot fetch(MonitorProperties.HostTarget target) {
        try {
            Map<String, Object> raw = webClient.get()
                    .uri(target.getAgentUrl() + "/agent/metrics")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(timeout)
                    .block();
            if (raw == null) {
                return HostSnapshot.down(target.getId(), target.getName(), "empty response");
            }
            return parse(target, raw);
        } catch (Exception e) {
            return HostSnapshot.down(target.getId(), target.getName(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private HostSnapshot parse(MonitorProperties.HostTarget target, Map<String, Object> raw) {
        String hostname = str(raw, "hostname");
        Map<String, Object> hostMap = (Map<String, Object>) raw.get("host");
        List<Map<String, Object>> appList = (List<Map<String, Object>>) raw.getOrDefault("apps", List.of());

        HostSnapshot.HostInfo host = hostMap == null ? null : new HostSnapshot.HostInfo(
                hostname,
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

        return new HostSnapshot(
                target.getId(), target.getName(), Instant.now(),
                HostSnapshot.Status.UP, null, host, apps, List.of(), List.of(), List.of());
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }
    private static long longVal(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.longValue() : 0L;
    }
    private static int intVal(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.intValue() : 0;
    }
    private static double doubleVal(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
}
