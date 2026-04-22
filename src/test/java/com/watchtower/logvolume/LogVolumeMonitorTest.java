package com.watchtower.logvolume;

import com.watchtower.alarm.AlarmEngine;
import com.watchtower.config.MonitorProperties;
import com.watchtower.domain.AlarmEvent;
import com.watchtower.domain.LogVolumeStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LogVolumeMonitorTest {

    @Test
    void emptyConfig_isNoop(@TempDir Path tmp) {
        MonitorProperties props = new MonitorProperties();
        AlarmEngine engine = mock(AlarmEngine.class);
        SimpMessagingTemplate tpl = mock(SimpMessagingTemplate.class);
        LogVolumeMonitor monitor = new LogVolumeMonitor(props, engine, tpl);

        monitor.scan();

        verify(engine, never()).raiseExternal(anyString(), anyString(), any(), anyString(),
                any(), anyString(), anyDouble(), anyDouble());
        verify(tpl, never()).convertAndSend(anyString(), (Object) any());
    }

    @Test
    void fires_whenUsageAboveThreshold(@TempDir Path tmp) throws IOException {
        Path logs = tmp.resolve("logs");
        Files.createDirectories(logs);
        Files.write(logs.resolve("a.log"), new byte[900]);

        MonitorProperties props = new MonitorProperties();
        props.getAlarms().setLogVolumeThresholdPct(80);
        MonitorProperties.LogVolume v = new MonitorProperties.LogVolume();
        v.setId("app");
        v.setName("App Logs");
        v.setPath(logs.toString());
        v.setMaxBytes(1000);
        props.setLogVolumes(List.of(v));

        AlarmEngine engine = mock(AlarmEngine.class);
        SimpMessagingTemplate tpl = mock(SimpMessagingTemplate.class);
        LogVolumeMonitor monitor = new LogVolumeMonitor(props, engine, tpl);

        monitor.scan();

        ArgumentCaptor<Double> pct = ArgumentCaptor.forClass(Double.class);
        verify(engine).raiseExternal(eq(LogVolumeMonitor.HOST_ID), anyString(),
                eq(AlarmEvent.Type.LOG_VOLUME), eq("app"),
                eq(AlarmEvent.Severity.WARN), anyString(), pct.capture(), eq(80.0));
        assertThat(pct.getValue()).isEqualTo(90.0);

        List<LogVolumeStatus> snap = monitor.snapshot();
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).usedBytes()).isEqualTo(900);
        assertThat(snap.get(0).usedPct()).isEqualTo(90.0);
        verify(tpl).convertAndSend(eq("/topic/log-volumes"), (Object) any());
    }

    @Test
    void clears_whenUsageBelowThreshold(@TempDir Path tmp) throws IOException {
        Path logs = tmp.resolve("logs");
        Files.createDirectories(logs);
        Files.write(logs.resolve("a.log"), new byte[100]);

        MonitorProperties props = new MonitorProperties();
        props.getAlarms().setLogVolumeThresholdPct(80);
        MonitorProperties.LogVolume v = new MonitorProperties.LogVolume();
        v.setId("app");
        v.setPath(logs.toString());
        v.setMaxBytes(1000);
        props.setLogVolumes(List.of(v));

        AlarmEngine engine = mock(AlarmEngine.class);
        SimpMessagingTemplate tpl = mock(SimpMessagingTemplate.class);
        LogVolumeMonitor monitor = new LogVolumeMonitor(props, engine, tpl);

        monitor.scan();

        verify(engine).clearExternal(eq(LogVolumeMonitor.HOST_ID), anyString(),
                eq(AlarmEvent.Type.LOG_VOLUME), eq("app"));
        verify(engine, never()).raiseExternal(anyString(), anyString(), any(), anyString(),
                any(), anyString(), anyDouble(), anyDouble());
    }

    @Test
    void fires_crit_at95Pct(@TempDir Path tmp) throws IOException {
        Path logs = tmp.resolve("logs");
        Files.createDirectories(logs);
        Files.write(logs.resolve("a.log"), new byte[960]);

        MonitorProperties props = new MonitorProperties();
        props.getAlarms().setLogVolumeThresholdPct(80);
        MonitorProperties.LogVolume v = new MonitorProperties.LogVolume();
        v.setId("app");
        v.setPath(logs.toString());
        v.setMaxBytes(1000);
        props.setLogVolumes(List.of(v));

        AlarmEngine engine = mock(AlarmEngine.class);
        LogVolumeMonitor monitor = new LogVolumeMonitor(props, engine, mock(SimpMessagingTemplate.class));

        monitor.scan();

        verify(engine).raiseExternal(anyString(), anyString(),
                eq(AlarmEvent.Type.LOG_VOLUME), eq("app"),
                eq(AlarmEvent.Severity.CRIT), anyString(), anyDouble(), anyDouble());
    }

    @Test
    void missingPath_reportsErrorWithoutCrashing(@TempDir Path tmp) {
        MonitorProperties props = new MonitorProperties();
        MonitorProperties.LogVolume v = new MonitorProperties.LogVolume();
        v.setId("missing");
        v.setPath(tmp.resolve("does-not-exist").toString());
        v.setMaxBytes(1000);
        props.setLogVolumes(List.of(v));

        AlarmEngine engine = mock(AlarmEngine.class);
        LogVolumeMonitor monitor = new LogVolumeMonitor(props, engine, mock(SimpMessagingTemplate.class));

        monitor.scan();

        List<LogVolumeStatus> snap = monitor.snapshot();
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).usedBytes()).isZero();
        verify(engine).clearExternal(anyString(), anyString(), eq(AlarmEvent.Type.LOG_VOLUME), eq("missing"));
    }

    @Test
    void droppedVolumes_areRemovedFromSnapshot(@TempDir Path tmp) throws IOException {
        Path logs = tmp.resolve("logs");
        Files.createDirectories(logs);
        Files.write(logs.resolve("a.log"), new byte[50]);

        MonitorProperties props = new MonitorProperties();
        MonitorProperties.LogVolume v = new MonitorProperties.LogVolume();
        v.setId("app");
        v.setPath(logs.toString());
        v.setMaxBytes(1000);
        props.setLogVolumes(new java.util.ArrayList<>(List.of(v)));

        AlarmEngine engine = mock(AlarmEngine.class);
        LogVolumeMonitor monitor = new LogVolumeMonitor(props, engine, mock(SimpMessagingTemplate.class));
        monitor.scan();
        assertThat(monitor.snapshot()).hasSize(1);

        props.setLogVolumes(List.of());
        monitor.scan();
        assertThat(monitor.snapshot()).isEmpty();
    }
}
