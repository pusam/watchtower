package com.watchtower.persistence;

import com.watchtower.domain.AlarmEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
public class AlarmRepository {

    private final JdbcTemplate jdbc;

    public AlarmRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(AlarmEvent ev, String qualifier) {
        try {
            jdbc.update(
                    "INSERT INTO alarm_event(id, host_id, host_name, type, severity, message, " +
                    "value_num, threshold_num, qualifier, fired_at, resolved_at, state, updated_at, " +
                    "acknowledged, acknowledged_at, acknowledged_by) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(id) DO UPDATE SET " +
                    "severity = excluded.severity, message = excluded.message, " +
                    "value_num = excluded.value_num, threshold_num = excluded.threshold_num, " +
                    "resolved_at = excluded.resolved_at, state = excluded.state, " +
                    "updated_at = excluded.updated_at, " +
                    "acknowledged = excluded.acknowledged, " +
                    "acknowledged_at = excluded.acknowledged_at, " +
                    "acknowledged_by = excluded.acknowledged_by",
                    ev.id(), ev.hostId(), ev.hostName(),
                    ev.type().name(), ev.severity().name(), ev.message(),
                    ev.value(), ev.threshold(), qualifier,
                    ev.firedAt() == null ? 0L : ev.firedAt().toEpochMilli(),
                    ev.resolvedAt() == null ? null : ev.resolvedAt().toEpochMilli(),
                    ev.state().name(),
                    System.currentTimeMillis(),
                    ev.acknowledged() ? 1 : 0,
                    ev.acknowledgedAt() == null ? null : ev.acknowledgedAt().toEpochMilli(),
                    ev.acknowledgedBy());
        } catch (Exception e) {
            log.warn("alarm persist failed id={} err={}", ev.id(), e.getMessage());
        }
    }

    public List<AlarmEvent> loadActive() {
        return jdbc.query(
                "SELECT * FROM alarm_event WHERE state = 'FIRING' ORDER BY fired_at DESC",
                ROW_MAPPER);
    }

    public List<AlarmEvent> loadRecent(int limit) {
        return jdbc.query(
                "SELECT * FROM alarm_event ORDER BY updated_at DESC LIMIT ?",
                ROW_MAPPER, limit);
    }

    public List<ActiveRow> loadActiveWithQualifier() {
        return jdbc.query(
                "SELECT id, host_id, host_name, type, severity, message, value_num, threshold_num, " +
                "qualifier, fired_at, resolved_at, state, acknowledged, acknowledged_at, acknowledged_by " +
                "FROM alarm_event WHERE state = 'FIRING'",
                (rs, i) -> new ActiveRow(mapEvent(rs), rs.getString("qualifier")));
    }

    public int deleteOlderThan(long cutoffEpochMs) {
        return jdbc.update(
                "DELETE FROM alarm_event WHERE state = 'RESOLVED' AND updated_at < ?",
                cutoffEpochMs);
    }

    public Map<String, Long> loadLastNotified() {
        Map<String, Long> map = new HashMap<>();
        jdbc.query("SELECT alarm_key, last_notified FROM alarm_notify", rs -> {
            map.put(rs.getString("alarm_key"), rs.getLong("last_notified"));
        });
        return map;
    }

    public void saveLastNotified(String key, long epochMs) {
        try {
            jdbc.update(
                    "INSERT INTO alarm_notify(alarm_key, last_notified) VALUES (?, ?) " +
                    "ON CONFLICT(alarm_key) DO UPDATE SET last_notified = excluded.last_notified",
                    key, epochMs);
        } catch (Exception e) {
            log.warn("lastNotified persist failed key={} err={}", key, e.getMessage());
        }
    }

    public void deleteLastNotified(String key) {
        try {
            jdbc.update("DELETE FROM alarm_notify WHERE alarm_key = ?", key);
        } catch (Exception e) {
            log.warn("lastNotified delete failed key={} err={}", key, e.getMessage());
        }
    }

    public record ActiveRow(AlarmEvent event, String qualifier) {}

    private static Instant toInstant(long ms) {
        return ms == 0 ? null : Instant.ofEpochMilli(ms);
    }

    private static AlarmEvent mapEvent(ResultSet rs) throws SQLException {
        boolean acked = rs.getInt("acknowledged") != 0;
        Object ackAtObj = rs.getObject("acknowledged_at");
        Instant ackAt = ackAtObj == null ? null : Instant.ofEpochMilli(((Number) ackAtObj).longValue());
        return new AlarmEvent(
                rs.getString("id"),
                rs.getString("host_id"),
                rs.getString("host_name"),
                AlarmEvent.Type.valueOf(rs.getString("type")),
                AlarmEvent.Severity.valueOf(rs.getString("severity")),
                rs.getString("message"),
                rs.getDouble("value_num"),
                rs.getDouble("threshold_num"),
                toInstant(rs.getLong("fired_at")),
                rs.getObject("resolved_at") == null ? null : toInstant(rs.getLong("resolved_at")),
                AlarmEvent.State.valueOf(rs.getString("state")),
                acked,
                ackAt,
                rs.getString("acknowledged_by"));
    }

    private static final RowMapper<AlarmEvent> ROW_MAPPER = (rs, i) -> mapEvent(rs);
}
