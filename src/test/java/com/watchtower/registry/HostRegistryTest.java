package com.watchtower.registry;

import com.watchtower.config.MonitorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HostRegistryTest {

    private HostRegistry registry;

    @BeforeEach
    void setUp() {
        MonitorProperties props = new MonitorProperties();
        props.getSecurity().setMaxRegistrationsPerMinute(3);
        registry = new HostRegistry(props);
    }

    @Test
    void tryConsume_allowsUpToLimit_thenBlocks() {
        assertThat(registry.tryConsume("host-a")).isTrue();
        assertThat(registry.tryConsume("host-a")).isTrue();
        assertThat(registry.tryConsume("host-a")).isTrue();
        assertThat(registry.tryConsume("host-a")).isFalse();
    }

    @Test
    void tryConsume_isPerHost() {
        for (int i = 0; i < 3; i++) registry.tryConsume("host-a");
        assertThat(registry.tryConsume("host-b")).isTrue();
    }

    @Test
    void cleanupRateLimitBuckets_removesExpiredEntries() throws Exception {
        registry.tryConsume("expired-host");
        // Force window into the past via reflection on the private map entry.
        java.lang.reflect.Field f = HostRegistry.class.getDeclaredField("rateLimitBuckets");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = (java.util.Map<String, Object>) f.get(registry);
        Object entry = map.get("expired-host");
        java.lang.reflect.Field ws = entry.getClass().getDeclaredField("windowStart");
        ws.setAccessible(true);
        // windowStart is final primitive long — can only set via setLong on the same field
        // Instead, swap the entry with a fresh one having old windowStart.
        Class<?> entryCls = entry.getClass();
        java.lang.reflect.Constructor<?> ctor = entryCls.getDeclaredConstructor(long.class, java.util.concurrent.atomic.AtomicInteger.class);
        ctor.setAccessible(true);
        Object oldEntry = ctor.newInstance(System.currentTimeMillis() - 10 * 60_000L,
                new java.util.concurrent.atomic.AtomicInteger(1));
        map.put("expired-host", oldEntry);

        registry.cleanupRateLimitBuckets();

        assertThat(map).doesNotContainKey("expired-host");
    }

    @Test
    void register_addsNewHost() {
        HostRegistry.RegisteredHost h = registry.register("server-x", "X", "http://1.2.3.4:19090");
        assertThat(h.target().getId()).isEqualTo("server-x");
        assertThat(h.target().getAgentUrl()).isEqualTo("http://1.2.3.4:19090");
        assertThat(registry.allHosts()).anySatisfy(r ->
                assertThat(r.target().getId()).isEqualTo("server-x"));
    }
}
