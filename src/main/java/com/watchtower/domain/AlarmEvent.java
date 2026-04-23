package com.watchtower.domain;

import java.time.Instant;

public record AlarmEvent(
        String id,
        String hostId,
        String hostName,
        Type type,
        Severity severity,
        String message,
        double value,
        double threshold,
        Instant firedAt,
        Instant resolvedAt,
        State state,
        boolean acknowledged,
        Instant acknowledgedAt,
        String acknowledgedBy
) {
    public enum Type { CPU, MEMORY, DISK, HOST_DOWN, ERROR_RATE, SLOW_RESPONSE, CERT_EXPIRING, ENDPOINT_DOWN, ENDPOINT_SLOW, LOG_VOLUME, ANOMALY }
    public enum Severity { INFO, WARN, CRIT }
    public enum State { FIRING, RESOLVED }

    public AlarmEvent resolve(Instant at) {
        return new AlarmEvent(id, hostId, hostName, type, severity, message, value, threshold,
                firedAt, at, State.RESOLVED, acknowledged, acknowledgedAt, acknowledgedBy);
    }

    public AlarmEvent acknowledge(Instant at, String by) {
        return new AlarmEvent(id, hostId, hostName, type, severity, message, value, threshold,
                firedAt, resolvedAt, state, true, at, by);
    }
}
