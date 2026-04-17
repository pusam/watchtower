package com.watchtower.domain;

public record SlowQueryStat(
        String pattern,
        String sample,
        String database,
        long count,
        long avgElapsedMs,
        long p50Ms,
        long p95Ms,
        long p99Ms,
        long maxElapsedMs,
        long totalRowsExamined,
        long maxRowsExamined,
        long lastSeen
) {}
