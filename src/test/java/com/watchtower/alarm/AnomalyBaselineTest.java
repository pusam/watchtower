package com.watchtower.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnomalyBaselineTest {

    @Test
    void returnsNaNBeforeMinSamples() {
        AnomalyBaseline b = new AnomalyBaseline(100);
        for (int i = 0; i < 10; i++) b.record("h1", "cpu", 20.0);
        assertThat(b.zScore("h1", "cpu", 50.0, 30)).isNaN();
    }

    @Test
    void returnsNaNWhenVarianceIsZero() {
        AnomalyBaseline b = new AnomalyBaseline(100);
        for (int i = 0; i < 50; i++) b.record("h1", "cpu", 25.0);
        // All samples equal → std = 0 → z is ill-defined, should return NaN not Infinity
        assertThat(b.zScore("h1", "cpu", 25.0, 30)).isNaN();
    }

    @Test
    void flagsObviousSpike() {
        AnomalyBaseline b = new AnomalyBaseline(100);
        // 60 samples around 20% with small noise
        for (int i = 0; i < 60; i++) b.record("h1", "cpu", 20.0 + (i % 4) * 0.5);
        double z = b.zScore("h1", "cpu", 80.0, 30);
        assertThat(z).isGreaterThan(10.0);
    }

    @Test
    void windowRolls() {
        AnomalyBaseline b = new AnomalyBaseline(20);
        for (int i = 0; i < 100; i++) b.record("h1", "cpu", i < 50 ? 80.0 : 20.0);
        // Only the last 20 (all 20.0) should be in the window now.
        assertThat(b.sampleCount("h1", "cpu")).isEqualTo(20);
        // Baseline is ~20 with zero variance → NaN (not a panic alert at 20)
        assertThat(b.zScore("h1", "cpu", 20.0, 10)).isNaN();
    }

    @Test
    void isolatesKeys() {
        AnomalyBaseline b = new AnomalyBaseline(100);
        for (int i = 0; i < 50; i++) b.record("h1", "cpu", 20.0 + (i % 3));
        assertThat(b.sampleCount("h2", "cpu")).isZero();
        assertThat(b.sampleCount("h1", "mem")).isZero();
    }
}
