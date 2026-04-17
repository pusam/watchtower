package com.watchtower.web;

import com.watchtower.alarm.AlarmEngine;
import com.watchtower.analytics.EndpointStatsService;
import com.watchtower.analytics.SlowQueryStatsService;
import com.watchtower.domain.AlarmEvent;
import com.watchtower.domain.EndpointStat;
import com.watchtower.domain.HostSnapshot;
import com.watchtower.domain.SlowQueryStat;
import com.watchtower.store.MetricsStore;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}
