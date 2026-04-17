package com.watchtower.collector;

import com.watchtower.config.MonitorProperties;
import com.watchtower.domain.HostSnapshot;
import com.watchtower.registry.HostRegistry;
import com.watchtower.registry.HostRegistry.RegisteredHost;
import com.watchtower.store.MetricsStore;
import com.watchtower.websocket.MetricsPublisher;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsCollector {

    private final MonitorProperties properties;
    private final AgentClient agentClient;
    private final MetricsStore store;
    private final MetricsPublisher publisher;
    private final HostRegistry registry;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Scheduled(fixedDelayString = "${watchtower.poll-interval-ms:5000}")
    public void collect() {
        List<RegisteredHost> staleHosts = registry.staleHosts(properties.getPushStaleThresholdMs());
        if (staleHosts.isEmpty()) return;

        for (RegisteredHost host : staleHosts) {
            executor.submit(() -> {
                HostSnapshot snapshot = agentClient.fetch(host.target());
                store.put(snapshot);
                publisher.publish(snapshot);
                log.debug("pull fallback {} -> {}", host.target().getId(), snapshot.status());
            });
        }
    }
}
