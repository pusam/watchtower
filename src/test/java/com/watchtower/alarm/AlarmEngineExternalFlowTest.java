package com.watchtower.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.watchtower.analytics.EndpointStatsService;
import com.watchtower.config.MonitorProperties;
import com.watchtower.domain.AlarmEvent;
import com.watchtower.persistence.AlarmRepository;
import com.watchtower.store.MetricsStore;
import com.watchtower.websocket.MetricsPublisher;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

class AlarmEngineExternalFlowTest {

    private AlarmEngine engine;
    private MetricsPublisher publisher;
    private NotificationDispatcher dispatcher;
    private MaintenanceService maintenance;

    @BeforeEach
    void setUp() {
        MonitorProperties props = new MonitorProperties();
        props.getAlarms().setBreachDurationSec(0);
        props.getAlarms().setCooldownSeconds(3600);
        props.getPersistence().setEnabled(false);

        MetricsStore store = Mockito.mock(MetricsStore.class);
        publisher = Mockito.mock(MetricsPublisher.class);
        dispatcher = Mockito.mock(NotificationDispatcher.class);
        EndpointStatsService stats = Mockito.mock(EndpointStatsService.class);
        maintenance = Mockito.mock(MaintenanceService.class);
        Mockito.when(maintenance.isMuted(any())).thenReturn(false);

        @SuppressWarnings("unchecked")
        ObjectProvider<AlarmRepository> repoProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(repoProvider.getIfAvailable()).thenReturn(null);

        engine = new AlarmEngine(store, props, publisher, dispatcher, stats, repoProvider, maintenance);
    }

    @Test
    void raiseExternal_newAlarm_appearsInActiveAndHistory() {
        engine.raiseExternal("srv", "Srv", AlarmEvent.Type.ERROR_RATE, null,
                AlarmEvent.Severity.WARN, "high err", 10.0, 5.0);

        assertThat(engine.activeAlarms()).hasSize(1);
        AlarmEvent e = engine.activeAlarms().get(0);
        assertThat(e.state()).isEqualTo(AlarmEvent.State.FIRING);
        assertThat(e.hostId()).isEqualTo("srv");
        assertThat(engine.recentHistory()).anyMatch(h -> h.id().equals(e.id()));
        verify(dispatcher).dispatch(any());
    }

    @Test
    void raiseExternal_duplicateDoesNotDispatchAgainWithinCooldown() {
        engine.raiseExternal("srv", "Srv", AlarmEvent.Type.ERROR_RATE, null,
                AlarmEvent.Severity.WARN, "high err", 10.0, 5.0);
        Mockito.reset(dispatcher);

        engine.raiseExternal("srv", "Srv", AlarmEvent.Type.ERROR_RATE, null,
                AlarmEvent.Severity.WARN, "high err", 11.0, 5.0);

        verify(dispatcher, never()).dispatch(any());
        assertThat(engine.activeAlarms()).hasSize(1);
    }

    @Test
    void clearExternal_movesAlarmToResolvedAndNotifies() {
        engine.raiseExternal("srv", "Srv", AlarmEvent.Type.ERROR_RATE, null,
                AlarmEvent.Severity.WARN, "high err", 10.0, 5.0);
        AlarmEvent firing = engine.activeAlarms().get(0);
        Mockito.reset(dispatcher);

        engine.clearExternal("srv", "Srv", AlarmEvent.Type.ERROR_RATE, null);

        assertThat(engine.activeAlarms()).isEmpty();
        List<AlarmEvent> history = engine.recentHistory();
        assertThat(history).anyMatch(h -> h.id().equals(firing.id())
                && h.state() == AlarmEvent.State.RESOLVED);
        verify(dispatcher).dispatch(any());
    }

    @Test
    void clearExternal_withoutMatchingActive_isNoOp() {
        engine.clearExternal("srv", "Srv", AlarmEvent.Type.HOST_DOWN, null);
        assertThat(engine.activeAlarms()).isEmpty();
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void maintenanceMuted_suppressesDispatchOnTriggerAndResolve() {
        Mockito.when(maintenance.isMuted("srv")).thenReturn(true);

        engine.raiseExternal("srv", "Srv", AlarmEvent.Type.ERROR_RATE, null,
                AlarmEvent.Severity.WARN, "high err", 10.0, 5.0);
        engine.clearExternal("srv", "Srv", AlarmEvent.Type.ERROR_RATE, null);

        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void qualifierSegmentsAlarmsPerResource() {
        engine.raiseExternal("srv", "Srv", AlarmEvent.Type.DISK, "/",
                AlarmEvent.Severity.WARN, "disk / full", 90.0, 85.0);
        engine.raiseExternal("srv", "Srv", AlarmEvent.Type.DISK, "/var",
                AlarmEvent.Severity.WARN, "disk /var full", 91.0, 85.0);

        assertThat(engine.activeAlarms()).hasSize(2);

        engine.clearExternal("srv", "Srv", AlarmEvent.Type.DISK, "/");
        assertThat(engine.activeAlarms()).hasSize(1);
        assertThat(engine.activeAlarms().get(0).message()).contains("/var");
    }
}
