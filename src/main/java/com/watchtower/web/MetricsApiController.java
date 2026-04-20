package com.watchtower.web;

import com.watchtower.alarm.AlarmEngine;
import com.watchtower.alarm.MaintenanceService;
import com.watchtower.analytics.EndpointStatsService;
import com.watchtower.analytics.SlowQueryStatsService;
import com.watchtower.domain.AlarmEvent;
import com.watchtower.domain.EndpointStat;
import com.watchtower.domain.HostSnapshot;
import com.watchtower.domain.ProbeResult;
import com.watchtower.domain.SlowQueryStat;
import com.watchtower.persistence.SnapshotRepository;
import com.watchtower.probe.SyntheticProbeService;
import com.watchtower.store.MetricsStore;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MetricsApiController {

    private final MetricsStore store;
    private final AlarmEngine alarmEngine;
    private final EndpointStatsService endpointStats;
    private final SlowQueryStatsService slowQueryStats;
    private final SyntheticProbeService probeService;
    private final MaintenanceService maintenanceService;
    private final ObjectProvider<SnapshotRepository> snapshotRepoProvider;

    @GetMapping("/metrics")
    public List<HostSnapshot> latestAll() {
        return store.latestAll();
    }

    @GetMapping("/metrics/{hostId}")
    public HostSnapshot latest(@PathVariable String hostId) {
        return store.latest(hostId);
    }

    @GetMapping("/metrics/{hostId}/history")
    public List<HostSnapshot> history(@PathVariable String hostId) {
        return store.history(hostId);
    }

    @GetMapping("/xlog")
    public List<HostSnapshot.RequestLog> xlogAll() {
        return store.xlogAll();
    }

    @GetMapping("/xlog/{hostId}")
    public List<HostSnapshot.RequestLog> xlog(@PathVariable String hostId) {
        return store.xlog(hostId);
    }

    @GetMapping("/alarms")
    public Map<String, List<AlarmEvent>> alarms() {
        return Map.of(
                "active", alarmEngine.activeAlarms(),
                "history", alarmEngine.recentHistory()
        );
    }

    @GetMapping("/endpoints")
    public List<EndpointStat> endpoints(@RequestParam(required = false) String hostId,
                                        @RequestParam(defaultValue = "300") long windowSec) {
        return endpointStats.compute(hostId, windowSec);
    }

    @GetMapping("/status-distribution")
    public List<EndpointStatsService.StatusBucket> statusDistribution(
            @RequestParam(required = false) String hostId,
            @RequestParam(defaultValue = "300") long windowSec,
            @RequestParam(defaultValue = "15") int bucketSec) {
        return endpointStats.statusDistribution(hostId, windowSec, bucketSec);
    }

    @GetMapping("/slow-queries")
    public List<SlowQueryStat> slowQueries(@RequestParam(required = false) String hostId,
                                            @RequestParam(defaultValue = "900") long windowSec) {
        return slowQueryStats.compute(hostId, windowSec);
    }

    @GetMapping("/slow-queries/recent")
    public List<HostSnapshot.SlowQuery> slowQueriesRecent(@RequestParam(required = false) String hostId) {
        return hostId == null || hostId.isBlank() ? store.slowQueriesAll() : store.slowQueries(hostId);
    }

    @GetMapping("/probes")
    public List<ProbeResult> probes() {
        return probeService.currentResults();
    }

    @PostMapping("/alarms/{id}/ack")
    public ResponseEntity<Map<String, Object>> ackAlarm(@PathVariable String id, Principal principal) {
        String user = principal == null ? "unknown" : principal.getName();
        boolean ok = alarmEngine.acknowledge(id, user);
        return ok
                ? ResponseEntity.ok(Map.of("acknowledged", true, "by", user))
                : ResponseEntity.status(404).body(Map.of("acknowledged", false, "error", "alarm not found or already resolved"));
    }

    @PostMapping("/maintenance/mute")
    public Map<String, Object> mute(@RequestBody(required = false) MuteRequest req) {
        long dur = req == null || req.durationSec() == null ? 3600 : Math.max(60, req.durationSec());
        if (req != null && req.hostId() != null && !req.hostId().isBlank()) {
            maintenanceService.muteHostFor(req.hostId(), dur);
            return Map.of("muted", true, "hostId", req.hostId(), "durationSec", dur);
        }
        maintenanceService.muteAllFor(dur);
        return Map.of("muted", true, "scope", "all", "durationSec", dur);
    }

    @PostMapping("/maintenance/unmute")
    public Map<String, Object> unmute() {
        maintenanceService.unmuteAll();
        return Map.of("muted", false);
    }

    public record MuteRequest(String hostId, Long durationSec) {}

    public record TimeSeriesPoint(long ts, Double cpu, Double mem, Double diskMax, Double load1,
                                  Long rxBps, Long txBps) {}

    @GetMapping("/metrics/{hostId}/range")
    public List<TimeSeriesPoint> range(@PathVariable String hostId,
                                       @RequestParam long from,
                                       @RequestParam long to,
                                       @RequestParam(defaultValue = "300") int maxPoints) {
        SnapshotRepository repo = snapshotRepoProvider.getIfAvailable();
        if (repo == null || from >= to) return List.of();
        List<HostSnapshot> snaps = repo.loadRange(hostId, from, to, maxPoints);
        List<TimeSeriesPoint> out = new ArrayList<>(snaps.size());
        for (HostSnapshot s : snaps) {
            if (s.timestamp() == null) continue;
            HostSnapshot.HostInfo h = s.host();
            Double cpu = h == null ? null : h.cpuUsedPct();
            Double mem = (h == null || h.memTotal() <= 0) ? null : (h.memUsed() * 100.0) / h.memTotal();
            Double diskMax = null;
            if (h != null && h.disks() != null) {
                double max = 0;
                for (HostSnapshot.DiskInfo d : h.disks()) {
                    if (d.total() > 0) max = Math.max(max, (d.used() * 100.0) / d.total());
                }
                diskMax = max;
            }
            Double load1 = h == null ? null : h.loadAvg1();
            Long rx = h == null ? null : h.netRxBps();
            Long tx = h == null ? null : h.netTxBps();
            out.add(new TimeSeriesPoint(s.timestamp().toEpochMilli(), cpu, mem, diskMax, load1, rx, tx));
        }
        return out;
    }
}
