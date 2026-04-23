package com.watchtower.persistence;

import com.watchtower.domain.ProbeResult;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
public class ProbeResultRepository {

    private final JdbcTemplate jdbc;
    private final DbDialect dialect;

    public ProbeResultRepository(JdbcTemplate jdbc, DbDialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    public void save(ProbeResult r) {
        try {
            long ts = r.checkedAt() == null ? System.currentTimeMillis() : r.checkedAt().toEpochMilli();
            String sql = dialect == DbDialect.POSTGRES
                    ? "INSERT INTO probe_result(probe_id, ts, status, latency_ms, http_code, message) " +
                      "VALUES (?, ?, ?, ?, ?, ?) " +
                      "ON CONFLICT (probe_id, ts) DO UPDATE SET " +
                      "  status = EXCLUDED.status, latency_ms = EXCLUDED.latency_ms, " +
                      "  http_code = EXCLUDED.http_code, message = EXCLUDED.message"
                    : "INSERT OR REPLACE INTO probe_result(probe_id, ts, status, latency_ms, http_code, message) " +
                      "VALUES (?, ?, ?, ?, ?, ?)";
            jdbc.update(sql,
                    r.probeId(), ts, r.status().name(), r.elapsedMs(), r.statusCode(), r.error());
        } catch (Exception e) {
            log.warn("probe_result persist failed id={}", r.probeId(), e);
        }
    }

    public int deleteOlderThan(long cutoffEpochMs) {
        return jdbc.update("DELETE FROM probe_result WHERE ts < ?", cutoffEpochMs);
    }

    public List<Row> loadRange(String probeId, long fromMs, long toMs, int maxPoints) {
        int safeMax = Math.max(1, maxPoints);
        return jdbc.query(
                "SELECT probe_id, ts, status, latency_ms, http_code, message FROM (" +
                "  SELECT probe_id, ts, status, latency_ms, http_code, message, " +
                "         ROW_NUMBER() OVER (ORDER BY ts) AS rn, " +
                "         COUNT(*) OVER () AS total " +
                "  FROM probe_result WHERE probe_id = ? AND ts BETWEEN ? AND ?" +
                ") t WHERE (rn - 1) % (CASE WHEN total > ? THEN total / ? ELSE 1 END) = 0 " +
                "ORDER BY ts ASC",
                (rs, i) -> new Row(
                        rs.getString("probe_id"),
                        Instant.ofEpochMilli(rs.getLong("ts")),
                        rs.getString("status"),
                        rs.getObject("latency_ms") == null ? null : rs.getLong("latency_ms"),
                        rs.getObject("http_code") == null ? null : rs.getInt("http_code"),
                        rs.getString("message")),
                probeId, fromMs, toMs, safeMax, safeMax);
    }

    public record Row(String probeId, Instant ts, String status, Long latencyMs, Integer httpCode, String message) {}
}
