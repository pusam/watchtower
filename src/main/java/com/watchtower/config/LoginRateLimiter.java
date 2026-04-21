package com.watchtower.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * IP-keyed failed-login rate limiter. Tracks basic-auth failures per remote address
 * and blocks further attempts for a lockout window once the failure threshold is hit
 * inside a sliding window.
 */
@Slf4j
@Component
public class LoginRateLimiter {

    static final int MAX_FAILURES = 10;
    static final long WINDOW_MS = 5 * 60_000L;
    static final long LOCKOUT_MS = 15 * 60_000L;
    static final int MAX_TRACKED = 10_000;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public boolean isBlocked(String ip) {
        if (ip == null) return false;
        Entry e = entries.get(ip);
        return e != null && e.lockoutUntil > System.currentTimeMillis();
    }

    public void recordFailure(String ip) {
        if (ip == null) return;
        if (entries.size() > MAX_TRACKED && !entries.containsKey(ip)) {
            cleanup();
            if (entries.size() > MAX_TRACKED) return;
        }
        long now = System.currentTimeMillis();
        entries.compute(ip, (k, existing) -> {
            if (existing == null || now - existing.windowStart > WINDOW_MS) {
                return new Entry(now, 1, 0L);
            }
            int failures = existing.failures + 1;
            long lockout = failures >= MAX_FAILURES ? now + LOCKOUT_MS : existing.lockoutUntil;
            if (failures == MAX_FAILURES) {
                log.warn("login brute-force lockout triggered for ip={} ({} failures)", ip, failures);
            }
            return new Entry(existing.windowStart, failures, lockout);
        });
    }

    public void recordSuccess(String ip) {
        if (ip == null) return;
        Entry existing = entries.get(ip);
        if (existing != null && existing.lockoutUntil <= System.currentTimeMillis()) {
            entries.remove(ip);
        }
    }

    @Scheduled(fixedDelay = 5 * 60_000L)
    public void cleanup() {
        long now = System.currentTimeMillis();
        int before = entries.size();
        entries.entrySet().removeIf(e ->
                e.getValue().lockoutUntil < now
                        && (now - e.getValue().windowStart) > WINDOW_MS);
        int removed = before - entries.size();
        if (removed > 0) {
            log.debug("evicted {} stale login-attempt entries", removed);
        }
    }

    int trackedSize() {
        return entries.size();
    }

    record Entry(long windowStart, int failures, long lockoutUntil) {}
}
