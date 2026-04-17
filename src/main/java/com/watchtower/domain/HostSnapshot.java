package com.watchtower.domain;

import java.time.Instant;
import java.util.List;

public record HostSnapshot(
        String hostId,
        String displayName,
        Instant timestamp,
        Status status,
        String error,
        HostInfo host,
        List<AppProcess> apps,
        List<RequestLog> requests,
        List<Cert> certs,
        List<SlowQuery> slowQueries
) {
    public enum Status { UP, DOWN }

    public record HostInfo(
            String hostname,
            String osName,
            String kernelVersion,
            int cpuCores,
            double loadAvg1,
            double loadAvg5,
            double loadAvg15,
            double cpuUsedPct,
            long memTotal,
            long memUsed,
            long memFree,
            long memAvailable,
            long swapTotal,
            long swapUsed,
            long uptimeSeconds,
            long netRxBps,
            long netTxBps,
            int tcpEstablished,
            List<ListenPort> listenPorts,
            List<DiskInfo> disks
    ) {}

    public record ListenPort(
            int port,
            String proto,
            String process,
            long pid
    ) {}

    public record DiskInfo(
            String mount,
            String fsType,
            long total,
            long used,
            long usable
    ) {}

    public record AppProcess(
            String name,
            long pid,
            boolean registered,
            String cmdline,
            long memRss,
            long uptimeSeconds
    ) {}

    public record RequestLog(
            long timestamp,
            String method,
            String path,
            int status,
            long elapsedMs,
            long bytes,
            String remoteIp,
            String source
    ) {}

    public record Cert(
            String subject,
            String issuer,
            long notAfter,
            long daysLeft,
            String sans,
            String source
    ) {}

    public record SlowQuery(
            long timestamp,
            long elapsedMs,
            long lockMs,
            long rowsSent,
            long rowsExamined,
            String database,
            String user,
            String clientHost,
            String sql,
            String source
    ) {}

    public static HostSnapshot down(String hostId, String displayName, String error) {
        return new HostSnapshot(hostId, displayName, Instant.now(), Status.DOWN, error,
                null, List.of(), List.of(), List.of(), List.of());
    }
}
