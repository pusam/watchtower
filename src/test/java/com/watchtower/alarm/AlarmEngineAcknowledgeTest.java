package com.watchtower.alarm;

import com.watchtower.analytics.EndpointStatsService;
import com.watchtower.config.MonitorProperties;
import com.watchtower.domain.AlarmEvent;
import com.watchtower.persistence.AlarmRepository;
import com.watchtower.store.MetricsStore;
import com.watchtower.websocket.MetricsPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AlarmEngineAcknowledgeTest {

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
    void acknowledge_unknownId_returnsFalse() {
        assertThat(engine.acknowledge("no-such-id", "alice")).isFalse();
    }

    @Test
    void acknowledge_setsFlagAndPublishes() {
        engine.raiseExternal("srv", "Srv", AlarmEvent.Type.CPU, null,
                AlarmEvent.Severity.WARN, "high cpu", 95.0, 90.0);

        List<AlarmEvent> active = engine.activeAlarms();
        assertThat(active).hasSize(1);
        String id = active.get(0).id();

        boolean ok = engine.acknowledge(id, "alice");

        assertThat(ok).isTrue();
        AlarmEvent updated = engine.activeAlarms().get(0);
        assertThat(updated.acknowledged()).isTrue();
        assertThat(updated.acknowledgedBy()).isEqualTo("alice");

        verify(publisher, Mockito.atLeastOnce()).publishAlarm(any());
    }

    @Test
    void acknowledge_idempotent_doesNotDoubleDispatch() {
        engine.raiseExternal("srv", "Srv", AlarmEvent.Type.CPU, null,
                AlarmEvent.Severity.WARN, "high cpu", 95.0, 90.0);
        String id = engine.activeAlarms().get(0).id();
        engine.acknowledge(id, "alice");
        Mockito.reset(publisher);

        boolean ok = engine.acknowledge(id, "alice");

        assertThat(ok).isTrue();
        verify(publisher, never()).publishAlarm(any());
    }
}
