package com.watchtower.domain;

public record EndpointStat(
        String pathPattern,
        String method,
        long count,
        long errorCount,
        double errorRatePct,
        long avgElapsedMs,
        long p50Ms,
        long p95Ms,
        long p99Ms,
        long maxElapsedMs
) {}
