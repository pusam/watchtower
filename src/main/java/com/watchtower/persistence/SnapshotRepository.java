package com.watchtower.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchtower.domain.HostSnapshot;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
public class SnapshotRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DbDialect dialect;

    public SnapshotRepository(JdbcTemplate jdbc, ObjectMapper objectMapper, DbDialect dialect) {
        this.jdbc = jdbc;
        this.mapper = objectMapper;
        this.dialect = dialect;
    }

    public void save(HostSnapshot snap) {
        if (snap.hostId() == null || snap.timestamp() == null) return;
        try {
            String json = mapper.writeValueAsString(snap);
            String sql = dialect == DbDialect.POSTGRES
                    ? "INSERT INTO host_snapshot(host_id, ts, payload) VALUES (?, ?, ?) " +
                      "ON CONFLICT (host_id, ts) DO UPDATE SET payload = EXCLUDED.payload"
                    : "INSERT OR REPLACE INTO host_snapshot(host_id, ts, payload) VALUES (?, ?, ?)";
            jdbc.update(sql, snap.hostId(), snap.timestamp().toEpochMilli(), json);
        } catch (JsonProcessingException e) {
            log.warn("snapshot serialize failed host={}", snap.hostId(), e);
        } catch (Exception e) {
            log.warn("snapshot persist failed host={}", snap.hostId(), e);
        }
    }

    public List<HostSnapshot> loadRecentPerHost(int perHostLimit) {
        List<String> hosts = jdbc.queryForList(
                "SELECT DISTINCT host_id FROM host_snapshot", String.class);
        List<HostSnapshot> all = new ArrayList<>();
        for (String hostId : hosts) {
            List<String> rows = jdbc.queryForList(
                    "SELECT payload FROM host_snapshot WHERE host_id = ? ORDER BY ts DESC LIMIT ?",
                    String.class, hostId, perHostLimit);
            List<HostSnapshot> snaps = new ArrayList<>(rows.size());
            for (String json : rows) {
                try {
                    snaps.add(mapper.readValue(json, HostSnapshot.class));
                } catch (Exception e) {
                    log.warn("snapshot deserialize failed host={}", hostId, e);
                }
            }
            for (int i = snaps.size() - 1; i >= 0; i--) all.add(snaps.get(i));
        }
        return all;
    }

    public int deleteOlderThan(long cutoffEpochMs) {
        return jdbc.update("DELETE FROM host_snapshot WHERE ts < ?", cutoffEpochMs);
    }

    public List<HostSnapshot> loadRange(String hostId, long fromEpochMs, long toEpochMs, int maxPoints) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM host_snapshot WHERE host_id = ? AND ts BETWEEN ? AND ?",
                Long.class, hostId, fromEpochMs, toEpochMs);
        long count = total == null ? 0 : total;
        if (count == 0) return List.of();
        int stride = maxPoints <= 0 ? 1 : Math.max(1, (int) Math.ceil(count / (double) maxPoints));
        List<String> rows;
        if (stride <= 1) {
            rows = jdbc.queryForList(
                    "SELECT payload FROM host_snapshot WHERE host_id = ? AND ts BETWEEN ? AND ? ORDER BY ts ASC",
                    String.class, hostId, fromEpochMs, toEpochMs);
        } else {
            rows = jdbc.queryForList(
                    "SELECT payload FROM (" +
                    "  SELECT payload, ts, ROW_NUMBER() OVER (ORDER BY ts ASC) - 1 AS rn " +
                    "  FROM host_snapshot WHERE host_id = ? AND ts BETWEEN ? AND ?" +
                    ") WHERE rn % ? = 0 ORDER BY ts ASC",
                    String.class, hostId, fromEpochMs, toEpochMs, stride);
        }
        List<HostSnapshot> out = new ArrayList<>(rows.size());
        for (String json : rows) {
            try {
                out.add(mapper.readValue(json, HostSnapshot.class));
            } catch (Exception e) {
                log.warn("range deserialize failed host={}", hostId, e);
            }
        }
        return out;
    }
}
