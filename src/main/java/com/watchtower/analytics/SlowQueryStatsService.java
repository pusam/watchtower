package com.watchtower.analytics;

import com.watchtower.domain.HostSnapshot;
import com.watchtower.domain.SlowQueryStat;
import com.watchtower.store.MetricsStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SlowQueryStatsService {

    private static final Pattern STRING_LIT = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'");
    private static final Pattern DSTRING_LIT = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern NUM_LIT = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final Pattern IN_LIST = Pattern.compile("\\bIN\\s*\\([^)]*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WS = Pattern.compile("\\s+");

    private final MetricsStore store;

    public List<SlowQueryStat> compute(String hostId, long windowSeconds) {
        List<HostSnapshot.SlowQuery> source = hostId == null || hostId.isBlank()
                ? store.slowQueriesAll()
                : store.slowQueries(hostId);

        long cutoff = System.currentTimeMillis() - windowSeconds * 1000L;
        Map<String, List<HostSnapshot.SlowQuery>> groups = new HashMap<>();
        Map<String, HostSnapshot.SlowQuery> samples = new HashMap<>();
        for (HostSnapshot.SlowQuery q : source) {
            if (q.timestamp() < cutoff) continue;
            String key = normalize(q.sql());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(q);
            samples.putIfAbsent(key, q);
        }

        List<SlowQueryStat> out = new ArrayList<>(groups.size());
        for (Map.Entry<String, List<HostSnapshot.SlowQuery>> e : groups.entrySet()) {
            List<HostSnapshot.SlowQuery> items = e.getValue();
            long[] sorted = items.stream().mapToLong(HostSnapshot.SlowQuery::elapsedMs).sorted().toArray();
            long totalRows = items.stream().mapToLong(HostSnapshot.SlowQuery::rowsExamined).sum();
            long maxRows = items.stream().mapToLong(HostSnapshot.SlowQuery::rowsExamined).max().orElse(0);
            long lastSeen = items.stream().mapToLong(HostSnapshot.SlowQuery::timestamp).max().orElse(0);
            HostSnapshot.SlowQuery sample = samples.get(e.getKey());
            out.add(new SlowQueryStat(
                    e.getKey(),
                    sample == null ? "" : sample.sql(),
                    sample == null ? "" : sample.database(),
                    items.size(),
                    (long) average(sorted),
                    percentile(sorted, 50),
                    percentile(sorted, 95),
                    percentile(sorted, 99),
                    sorted.length == 0 ? 0 : sorted[sorted.length - 1],
                    totalRows,
                    maxRows,
                    lastSeen
            ));
        }
        out.sort((a, b) -> Long.compare(b.maxElapsedMs(), a.maxElapsedMs()));
        return out;
    }

    static String normalize(String sql) {
        if (sql == null || sql.isBlank()) return "-";
        String s = sql;
        s = STRING_LIT.matcher(s).replaceAll("?");
        s = DSTRING_LIT.matcher(s).replaceAll("?");
        s = IN_LIST.matcher(s).replaceAll("IN (?)");
        s = NUM_LIT.matcher(s).replaceAll("?");
        s = WS.matcher(s).replaceAll(" ").trim();
        if (s.length() > 400) s = s.substring(0, 400);
        return s;
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
}
