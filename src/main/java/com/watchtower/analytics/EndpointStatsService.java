package com.watchtower.analytics;

import com.watchtower.domain.EndpointStat;
import com.watchtower.domain.HostSnapshot;
import com.watchtower.store.MetricsStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EndpointStatsService {

    private static final Pattern NUMERIC_ID = Pattern.compile("/\\d+(?=/|$)");
    private static final Pattern UUID = Pattern.compile(
            "/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?=/|$)");
    private static final Pattern HEX = Pattern.compile("/[0-9a-fA-F]{16,}(?=/|$)");

    private final MetricsStore store;

    public List<EndpointStat> compute(String hostId, long windowSeconds) {
        List<HostSnapshot.RequestLog> source = hostId == null || hostId.isBlank()
                ? store.xlogAll()
                : store.xlog(hostId);

        long cutoff = System.currentTimeMillis() - windowSeconds * 1000L;
        Map<String, List<HostSnapshot.RequestLog>> groups = new HashMap<>();
        for (HostSnapshot.RequestLog r : source) {
            if (r.timestamp() < cutoff) continue;
            String key = (r.method() == null ? "?" : r.method()) + " " + normalize(r.path());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        List<EndpointStat> out = new ArrayList<>(groups.size());
        for (Map.Entry<String, List<HostSnapshot.RequestLog>> e : groups.entrySet()) {
            String[] split = e.getKey().split(" ", 2);
            String method = split[0];
            String path = split.length > 1 ? split[1] : "";
            List<HostSnapshot.RequestLog> items = e.getValue();
            long count = items.size();
            long errs = items.stream().filter(r -> r.status() >= 400).count();
            double errRate = count == 0 ? 0.0 : (errs * 100.0) / count;
            long[] sorted = items.stream().mapToLong(HostSnapshot.RequestLog::elapsedMs).sorted().toArray();
            long avg = sorted.length == 0 ? 0 : (long) (average(sorted));
            long p50 = percentile(sorted, 50);
            long p95 = percentile(sorted, 95);
            long p99 = percentile(sorted, 99);
            long max = sorted.length == 0 ? 0 : sorted[sorted.length - 1];
            out.add(new EndpointStat(path, method, count, errs, errRate, avg, p50, p95, p99, max));
        }
        out.sort((a, b) -> Long.compare(b.count(), a.count()));
        return out;
    }

    public double errorRate(String hostId, long windowSeconds) {
        List<HostSnapshot.RequestLog> items = store.xlog(hostId);
        long cutoff = System.currentTimeMillis() - windowSeconds * 1000L;
        long total = 0, errs = 0;
        for (HostSnapshot.RequestLog r : items) {
            if (r.timestamp() < cutoff) continue;
            total++;
            if (r.status() >= 500) errs++;
        }
        return total == 0 ? 0.0 : (errs * 100.0) / total;
    }

    public List<StatusBucket> statusDistribution(String hostId, long windowSeconds, int bucketSeconds) {
        List<HostSnapshot.RequestLog> items = hostId == null || hostId.isBlank()
                ? store.xlogAll()
                : store.xlog(hostId);
        long now = System.currentTimeMillis();
        long cutoff = now - windowSeconds * 1000L;
        long bucketMs = bucketSeconds * 1000L;
        Map<Long, int[]> buckets = new HashMap<>();
        for (HostSnapshot.RequestLog r : items) {
            if (r.timestamp() < cutoff) continue;
            long slot = (r.timestamp() / bucketMs) * bucketMs;
            int[] counts = buckets.computeIfAbsent(slot, k -> new int[4]);
            int idx = Math.min(3, Math.max(0, (r.status() / 100) - 2));
            counts[idx]++;
        }
        List<StatusBucket> result = new ArrayList<>();
        for (long slot = (cutoff / bucketMs) * bucketMs; slot <= now; slot += bucketMs) {
            int[] c = buckets.getOrDefault(slot, new int[4]);
            result.add(new StatusBucket(slot, c[0], c[1], c[2], c[3]));
        }
        return result;
    }

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) return "-";
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        path = UUID.matcher(path).replaceAll("/{uuid}");
        path = HEX.matcher(path).replaceAll("/{hex}");
        path = NUMERIC_ID.matcher(path).replaceAll("/{id}");
        if (path.length() > 200) path = path.substring(0, 200);
        return path;
    }

    private static double average(long[] arr) {
        long sum = 0;
        for (long v : arr) sum += v;
        return arr.length == 0 ? 0 : (double) sum / arr.length;
    }

    private static long percentile(long[] sorted, int p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil((p / 100.0) * sorted.length) - 1;
        return sorted[Math.min(Math.max(idx, 0), sorted.length - 1)];
    }

    public record StatusBucket(long timestamp, int s2xx, int s3xx, int s4xx, int s5xx) {}
}
