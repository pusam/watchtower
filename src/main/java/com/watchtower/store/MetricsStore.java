package com.watchtower.store;

import com.watchtower.config.MonitorProperties;
import com.watchtower.domain.HostSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class MetricsStore {

    private static final int XLOG_BUFFER_SIZE = 500;
    private static final int SLOW_QUERY_BUFFER_SIZE = 500;

    private final int historySize;
    private final Map<String, Deque<HostSnapshot>> store = new ConcurrentHashMap<>();
    private final Map<String, HostSnapshot> latest = new ConcurrentHashMap<>();
    private final Map<String, Deque<HostSnapshot.RequestLog>> xlog = new ConcurrentHashMap<>();
    private final Map<String, Deque<HostSnapshot.SlowQuery>> slowLog = new ConcurrentHashMap<>();

    public MetricsStore(MonitorProperties properties) {
        this.historySize = properties.getHistorySize();
    }

    public void put(HostSnapshot snapshot) {
        latest.put(snapshot.hostId(), snapshot);
        Deque<HostSnapshot> queue = store.computeIfAbsent(snapshot.hostId(), k -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(snapshot);
            while (queue.size() > historySize) {
                queue.pollFirst();
            }
        }
        if (snapshot.requests() != null && !snapshot.requests().isEmpty()) {
            Deque<HostSnapshot.RequestLog> log = xlog.computeIfAbsent(snapshot.hostId(), k -> new ArrayDeque<>());
            synchronized (log) {
                for (HostSnapshot.RequestLog r : snapshot.requests()) {
                    log.addLast(r);
                }
                while (log.size() > XLOG_BUFFER_SIZE) {
                    log.pollFirst();
                }
            }
        }
        if (snapshot.slowQueries() != null && !snapshot.slowQueries().isEmpty()) {
            Deque<HostSnapshot.SlowQuery> log = slowLog.computeIfAbsent(snapshot.hostId(), k -> new ArrayDeque<>());
            synchronized (log) {
                for (HostSnapshot.SlowQuery q : snapshot.slowQueries()) {
                    log.addLast(q);
                }
                while (log.size() > SLOW_QUERY_BUFFER_SIZE) {
                    log.pollFirst();
                }
            }
        }
    }

    public HostSnapshot latest(String hostId) {
        return latest.get(hostId);
    }

    public List<HostSnapshot> latestAll() {
        return new ArrayList<>(latest.values());
    }

    public List<HostSnapshot> history(String hostId) {
        Deque<HostSnapshot> queue = store.get(hostId);
        if (queue == null) return List.of();
        synchronized (queue) {
            return new ArrayList<>(queue);
        }
    }

    public List<HostSnapshot.RequestLog> xlog(String hostId) {
        Deque<HostSnapshot.RequestLog> log = xlog.get(hostId);
        if (log == null) return List.of();
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    public List<HostSnapshot.RequestLog> xlogAll() {
        List<HostSnapshot.RequestLog> all = new ArrayList<>();
        for (Deque<HostSnapshot.RequestLog> log : xlog.values()) {
            synchronized (log) {
                all.addAll(log);
            }
        }
        all.sort((a, b) -> Long.compare(b.timestamp(), a.timestamp()));
        return all.size() > XLOG_BUFFER_SIZE ? all.subList(0, XLOG_BUFFER_SIZE) : all;
    }

    public List<HostSnapshot.SlowQuery> slowQueries(String hostId) {
        Deque<HostSnapshot.SlowQuery> log = slowLog.get(hostId);
        if (log == null) return List.of();
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    public List<HostSnapshot.SlowQuery> slowQueriesAll() {
        List<HostSnapshot.SlowQuery> all = new ArrayList<>();
        for (Deque<HostSnapshot.SlowQuery> log : slowLog.values()) {
            synchronized (log) {
                all.addAll(log);
            }
        }
        all.sort((a, b) -> Long.compare(b.timestamp(), a.timestamp()));
        return all.size() > SLOW_QUERY_BUFFER_SIZE ? all.subList(0, SLOW_QUERY_BUFFER_SIZE) : all;
    }
}
