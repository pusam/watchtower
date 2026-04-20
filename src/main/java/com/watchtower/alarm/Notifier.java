package com.watchtower.alarm;

import com.watchtower.domain.AlarmEvent;

public interface Notifier {
    void send(AlarmEvent alarm);
}
