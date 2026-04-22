package com.watchtower.domain;

import java.time.Instant;

public record LogVolumeStatus(
        String id,
        String name,
        String path,
        long maxBytes,
        long usedBytes,
        double usedPct,
        Instant lastChecked,
        String error
) {}
