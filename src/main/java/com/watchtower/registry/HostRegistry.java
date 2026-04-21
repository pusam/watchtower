package com.watchtower.registry;

import com.watchtower.config.MonitorProperties;
import com.watchtower.config.MonitorProperties.HostTarget;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class HostRegistry {

    private final MonitorProperties properties;
    private final Map<String, RegisteredHost> hosts = new ConcurrentHashMap<>();
    private final Map<String, RateLimitEntry> rateLimitBuckets = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        if (properties.getHosts() == null) return;
        for (HostTarget target : properties.getHosts()) {
            hosts.put(target.getId(), new RegisteredHost(target, null, true));
        }
    }

    public RegisteredHost register(String hostId, String displayName, String agentUrl) {
        return hosts.compute(hostId, (id, existing) -> {
            HostTarget target;
            boolean manual;
            if (existing != null) {
                target = existing.target();
                manual = existing.manual();
                if (agentUrl != null) {
                    target.setAgentUrl(agentUrl);
                }
                if (displayName != null) {
                    target.setName(displayName);
                }
            } else {
                target = new HostTarget();
                target.setId(hostId);
                target.setName(displayName != null ? displayName : hostId);
                target.setAgentUrl(agentUrl);
                manual = false;
            }
            return new RegisteredHost(target, Instant.now(), manual);
        });
    }

    public void markPushed(String hostId) {
        hosts.computeIfPresent(hostId, (id, h) ->
                new RegisteredHost(h.target(), Instant.now(), h.manual()));
    }

    public List<RegisteredHost> staleHosts(long thresholdMs) {
        Instant threshold = Instant.now().minusMillis(thresholdMs);
        List<RegisteredHost> result = new ArrayList<>();
        for (RegisteredHost host : hosts.values()) {
            if (host.target().getAgentUrl() == null) continue;
            if (host.lastPush() == null || host.lastPush().isBefore(threshold)) {
                result.add(host);
            }
        }
        return result;
    }

    public List<RegisteredHost> allHosts() {
        return new ArrayList<>(hosts.values());
    }

    private static final int RATE_LIMIT_MAX_ENTRIES = 10_000;

    public boolean tryConsume(String hostId) {
        int maxPerMinute = properties.getSecurity().getMaxRegistrationsPerMinute();
        if (rateLimitBuckets.size() > RATE_LIMIT_MAX_ENTRIES && !rateLimitBuckets.containsKey(hostId)) {
            cleanupRateLimitBuckets();
            if (rateLimitBuckets.size() > RATE_LIMIT_MAX_ENTRIES) {
                log.warn("rate limit map saturated; rejecting hostId={}", hostId);
                return false;
            }
        }
        RateLimitEntry entry = rateLimitBuckets.compute(hostId, (id, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || now - existing.windowStart > 60_000) {
                return new RateLimitEntry(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });
        boolean allowed = entry.count.get() <= maxPerMinute;
        if (!allowed) {
            log.warn("rate limit exceeded for hostId={}", hostId);
        }
        return allowed;
    }

    @Scheduled(fixedDelay = 120_000L)
    public void cleanupRateLimitBuckets() {
        long cutoff = System.currentTimeMillis() - 180_000L;
        int before = rateLimitBuckets.size();
        rateLimitBuckets.entrySet().removeIf(e -> e.getValue().windowStart < cutoff);
        int removed = before - rateLimitBuckets.size();
        if (removed > 0) {
            log.debug("evicted {} stale rate-limit entries", removed);
        }
    }

    private static class RateLimitEntry {
        final long windowStart;
        final AtomicInteger count;
        RateLimitEntry(long windowStart, AtomicInteger count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }

    public record RegisteredHost(HostTarget target, Instant lastPush, boolean manual) {}
}
