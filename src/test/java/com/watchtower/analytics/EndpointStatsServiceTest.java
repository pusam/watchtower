package com.watchtower.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.watchtower.domain.EndpointStat;
import com.watchtower.domain.HostSnapshot;
import com.watchtower.store.MetricsStore;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EndpointStatsServiceTest {

    private static HostSnapshot.RequestLog req(long ts, String method, String path, int status, long elapsed) {
        return new HostSnapshot.RequestLog(ts, method, path, status, elapsed, 0, "127.0.0.1", "-");
    }

    @Test
    void errorRate_countsOnly5xxWithinWindow() {
        MetricsStore store = Mockito.mock(MetricsStore.class);
        long now = System.currentTimeMillis();
        Mockito.when(store.xlog("srv")).thenReturn(List.of(
                req(now - 1_000, "GET", "/a", 200, 10),
                req(now - 2_000, "GET", "/a", 500, 20),
                req(now - 3_000, "GET", "/a", 503, 30),
                req(now - 4_000, "GET", "/a", 404, 40),      // 4xx ignored by errorRate
                req(now - 120_000, "GET", "/a", 500, 10)     // outside 60s window
        ));

        EndpointStatsService svc = new EndpointStatsService(store);
        double rate = svc.errorRate("srv", 60);
        // 4 in-window requests, 2 are 5xx → 50%
        assertThat(rate).isEqualTo(50.0);
    }

    @Test
    void errorRate_emptyWindow_returnsZero() {
        MetricsStore store = Mockito.mock(MetricsStore.class);
        Mockito.when(store.xlog("srv")).thenReturn(List.of());
        assertThat(new EndpointStatsService(store).errorRate("srv", 60)).isZero();
    }

    @Test
    void compute_normalizesPathsAndAggregatesByEndpoint() {
        MetricsStore store = Mockito.mock(MetricsStore.class);
        long now = System.currentTimeMillis();
        Mockito.when(store.xlog("srv")).thenReturn(List.of(
                req(now, "GET", "/users/42/orders", 200, 10),
                req(now, "GET", "/users/17/orders", 500, 20),
                req(now, "GET", "/users/550e8400-e29b-41d4-a716-446655440000/orders", 200, 30),
                req(now, "POST", "/users/42/orders", 201, 15)
        ));

        EndpointStatsService svc = new EndpointStatsService(store);
        List<EndpointStat> stats = svc.compute("srv", 60);

        EndpointStat getOrders = stats.stream()
                .filter(s -> s.pathPattern().equals("/users/{id}/orders") && s.method().equals("GET"))
                .findFirst().orElseThrow();
        assertThat(getOrders.count()).isEqualTo(2);

        EndpointStat uuidOrders = stats.stream()
                .filter(s -> s.pathPattern().equals("/users/{uuid}/orders"))
                .findFirst().orElseThrow();
        assertThat(uuidOrders.count()).isEqualTo(1);

        EndpointStat postOrders = stats.stream()
                .filter(s -> s.pathPattern().equals("/users/{id}/orders") && s.method().equals("POST"))
                .findFirst().orElseThrow();
        assertThat(postOrders.count()).isEqualTo(1);
    }

    @Test
    void compute_computesPercentilesAndErrorRate() {
        MetricsStore store = Mockito.mock(MetricsStore.class);
        long now = System.currentTimeMillis();
        // 100 requests, elapsed 1..100ms, 10 of them are 5xx
        var logs = IntStream.rangeClosed(1, 100)
                .mapToObj(i -> req(now, "GET", "/a", i <= 10 ? 500 : 200, i))
                .toList();
        Mockito.when(store.xlog("srv")).thenReturn(logs);

        List<EndpointStat> stats = new EndpointStatsService(store).compute("srv", 60);
        assertThat(stats).hasSize(1);
        EndpointStat s = stats.get(0);
        assertThat(s.count()).isEqualTo(100);
        assertThat(s.errorCount()).isEqualTo(10);
        assertThat(s.errorRatePct()).isEqualTo(10.0);
        assertThat(s.maxElapsedMs()).isEqualTo(100);
        assertThat(s.p95Ms()).isEqualTo(95);
    }

    @Test
    void statusDistribution_bucketsByStatusClass() {
        MetricsStore store = Mockito.mock(MetricsStore.class);
        long now = System.currentTimeMillis();
        Mockito.when(store.xlog("srv")).thenReturn(List.of(
                req(now, "GET", "/a", 200, 10),
                req(now, "GET", "/a", 301, 10),
                req(now, "GET", "/a", 404, 10),
                req(now, "GET", "/a", 500, 10),
                req(now, "GET", "/a", 500, 10)
        ));

        var buckets = new EndpointStatsService(store).statusDistribution("srv", 60, 30);
        int s2 = buckets.stream().mapToInt(b -> b.s2xx()).sum();
        int s3 = buckets.stream().mapToInt(b -> b.s3xx()).sum();
        int s4 = buckets.stream().mapToInt(b -> b.s4xx()).sum();
        int s5 = buckets.stream().mapToInt(b -> b.s5xx()).sum();
        assertThat(s2).isEqualTo(1);
        assertThat(s3).isEqualTo(1);
        assertThat(s4).isEqualTo(1);
        assertThat(s5).isEqualTo(2);
    }
}
