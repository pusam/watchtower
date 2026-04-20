package com.watchtower.domain;

import java.time.Instant;

public record ProbeResult(
        String probeId,
        String name,
        String type,
        String target,
        Status status,
        int statusCode,
        long elapsedMs,
        Instant checkedAt,
        String error
) {
    public enum Status { UP, SLOW, DOWN }
}
