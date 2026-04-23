package com.watchtower.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import com.watchtower.config.MonitorProperties;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaintenanceServiceTest {

    private MaintenanceService newService(MonitorProperties props) {
        return new MaintenanceService(props);
    }

    @Test
    void unmutedHost_returnsFalse() {
        MaintenanceService m = newService(new MonitorProperties());
        assertThat(m.isMuted("srv-a")).isFalse();
    }

    @Test
    void muteAllFor_mutesEveryHostUntilExpiry() {
        MaintenanceService m = newService(new MonitorProperties());
        m.muteAllFor(5);
        assertThat(m.isMuted("srv-a")).isTrue();
        assertThat(m.isMuted("srv-b")).isTrue();

        m.unmuteAll();
        assertThat(m.isMuted("srv-a")).isFalse();
    }

    @Test
    void muteHostFor_mutesOnlyThatHost() {
        MaintenanceService m = newService(new MonitorProperties());
        m.muteHostFor("srv-a", 5);
        assertThat(m.isMuted("srv-a")).isTrue();
        assertThat(m.isMuted("srv-b")).isFalse();
    }

    @Test
    void muteHostFor_afterMuteAllFor_keepsAllHostsMuted() {
        MaintenanceService m = newService(new MonitorProperties());
        m.muteAllFor(60);
        m.muteHostFor("srv-a", 30);
        assertThat(m.isMuted("srv-a")).isTrue();
        assertThat(m.isMuted("srv-b")).isTrue();
    }

    @Test
    void unmuteAll_clearsBothAllScopeAndPerHostMutes() {
        MaintenanceService m = newService(new MonitorProperties());
        m.muteAllFor(60);
        m.muteHostFor("srv-a", 30);
        m.unmuteAll();
        assertThat(m.isMuted("srv-a")).isFalse();
        assertThat(m.isMuted("srv-b")).isFalse();
    }

    @Test
    void maintenanceWindowWithoutHostIds_mutesAll() {
        MonitorProperties props = new MonitorProperties();
        MonitorProperties.MaintenanceWindow w = new MonitorProperties.MaintenanceWindow();
        w.setName("night");
        w.setFrom(formatPast());
        w.setTo(formatFuture());
        w.setHostIds(List.of());
        props.getMaintenance().add(w);

        MaintenanceService m = newService(props);
        assertThat(m.isMuted("srv-a")).isTrue();
        assertThat(m.isMuted("srv-b")).isTrue();
    }

    @Test
    void maintenanceWindowHostFilter_mutesOnlyListedHosts() {
        MonitorProperties props = new MonitorProperties();
        MonitorProperties.MaintenanceWindow w = new MonitorProperties.MaintenanceWindow();
        w.setFrom(formatPast());
        w.setTo(formatFuture());
        w.setHostIds(List.of("srv-a"));
        props.getMaintenance().add(w);

        MaintenanceService m = newService(props);
        assertThat(m.isMuted("srv-a")).isTrue();
        assertThat(m.isMuted("srv-b")).isFalse();
    }

    @Test
    void expiredWindow_doesNotMute() {
        MonitorProperties props = new MonitorProperties();
        MonitorProperties.MaintenanceWindow w = new MonitorProperties.MaintenanceWindow();
        w.setFrom(DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(Instant.now().minusSeconds(600).atOffset(java.time.ZoneOffset.UTC)));
        w.setTo(DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(Instant.now().minusSeconds(60).atOffset(java.time.ZoneOffset.UTC)));
        props.getMaintenance().add(w);

        MaintenanceService m = newService(props);
        assertThat(m.isMuted("srv-a")).isFalse();
    }

    @Test
    void invalidWindowTimes_doesNotMuteAndDoesNotThrow() {
        MonitorProperties props = new MonitorProperties();
        MonitorProperties.MaintenanceWindow w = new MonitorProperties.MaintenanceWindow();
        w.setFrom("not-a-date");
        w.setTo("also-not");
        props.getMaintenance().add(w);

        MaintenanceService m = newService(props);
        assertThat(m.isMuted("srv-a")).isFalse();
    }

    private String formatPast() {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(Instant.now().minusSeconds(60).atOffset(java.time.ZoneOffset.UTC));
    }

    private String formatFuture() {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(Instant.now().plusSeconds(600).atOffset(java.time.ZoneOffset.UTC));
    }
}
