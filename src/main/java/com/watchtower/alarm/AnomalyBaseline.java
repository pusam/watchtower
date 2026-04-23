package com.watchtower.alarm;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rolling window of recent samples keyed by (hostId, metric). Reports the current
 * z-score against the window's running mean / std-dev so the alarm engine can flag
 * values that drift far from their normal baseline even when they stay under the
 * absolute threshold.
 *
 * <p>Implementation: fixed-capacity ring buffer per key plus lazily-computed stats.
 * Not sub-millisecond fast, but anomaly evaluation runs every {@code check-interval-ms}
 * (10s by default) so an O(n) recompute over a 360-element window is negligible.
 */
public class AnomalyBaseline {

    private final int windowSize;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public AnomalyBaseline(int windowSize) {
        this.windowSize = Math.max(2, windowSize);
    }

    public void record(String hostId, String metric, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return;
        windows.computeIfAbsent(key(hostId, metric), k -> new Window(windowSize)).add(value);
    }

    /**
     * @return z-score (how many std-devs above mean) or {@code NaN} if the window does
     *         not yet have enough samples or variance is degenerate.
     */
    public double zScore(String hostId, String metric, double value, int minSamples) {
        Window w = windows.get(key(hostId, metric));
        if (w == null) return Double.NaN;
        return w.zScore(value, minSamples);
    }

    public int sampleCount(String hostId, String metric) {
        Window w = windows.get(key(hostId, metric));
        return w == null ? 0 : w.size();
    }

    private String key(String hostId, String metric) {
        return hostId + "|" + metric;
    }

    private static final class Window {
        private final double[] buf;
        private int next;
        private int count;

        Window(int cap) { this.buf = new double[cap]; }

        synchronized void add(double v) {
            buf[next] = v;
            next = (next + 1) % buf.length;
            if (count < buf.length) count++;
        }

        synchronized int size() { return count; }

        synchronized double zScore(double v, int minSamples) {
            if (count < minSamples) return Double.NaN;
            double[] snap = Arrays.copyOf(buf, count);
            double mean = 0;
            for (double x : snap) mean += x;
            mean /= snap.length;
            double sq = 0;
            for (double x : snap) { double d = x - mean; sq += d * d; }
            double variance = sq / snap.length;
            double std = Math.sqrt(variance);
            if (std < 1e-6) return Double.NaN;
            return (v - mean) / std;
        }
    }
}
