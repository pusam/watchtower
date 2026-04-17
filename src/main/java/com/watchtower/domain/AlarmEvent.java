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
        State state
) {
    public enum Type { CPU, MEMORY, DISK, HOST_DOWN, ERROR_RATE, SLOW_RESPONSE, CERT_EXPIRING }
    public enum Severity { INFO, WARN, CRIT }
    public enum State { FIRING, RESOLVED }

    public AlarmEvent resolve(Instant at) {
        return new AlarmEvent(id, hostId, hostName, type, severity, message, value, threshold, firedAt, at, State.RESOLVED);
    }
}
