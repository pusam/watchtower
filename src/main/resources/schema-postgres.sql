CREATE TABLE IF NOT EXISTS host_snapshot (
    host_id    TEXT    NOT NULL,
    ts         BIGINT  NOT NULL,
    payload    TEXT    NOT NULL,
    PRIMARY KEY (host_id, ts)
);

CREATE INDEX IF NOT EXISTS idx_host_snapshot_ts ON host_snapshot(ts DESC);

CREATE TABLE IF NOT EXISTS alarm_event (
    id              TEXT    PRIMARY KEY,
    host_id         TEXT    NOT NULL,
    host_name       TEXT,
    type            TEXT    NOT NULL,
    severity        TEXT    NOT NULL,
    message         TEXT,
    value_num       DOUBLE PRECISION,
    threshold_num   DOUBLE PRECISION,
    qualifier       TEXT,
    fired_at        BIGINT  NOT NULL,
    resolved_at     BIGINT,
    state           TEXT    NOT NULL,
    updated_at      BIGINT  NOT NULL,
    acknowledged    SMALLINT NOT NULL DEFAULT 0,
    acknowledged_at BIGINT,
    acknowledged_by TEXT
);

CREATE INDEX IF NOT EXISTS idx_alarm_host_fired ON alarm_event(host_id, fired_at DESC);
CREATE INDEX IF NOT EXISTS idx_alarm_state      ON alarm_event(state);

CREATE TABLE IF NOT EXISTS alarm_notify (
    alarm_key      TEXT    PRIMARY KEY,
    last_notified  BIGINT  NOT NULL
);

CREATE TABLE IF NOT EXISTS probe_result (
    probe_id   TEXT    NOT NULL,
    ts         BIGINT  NOT NULL,
    status     TEXT    NOT NULL,
    latency_ms BIGINT,
    http_code  INTEGER,
    message    TEXT,
    PRIMARY KEY (probe_id, ts)
);
CREATE INDEX IF NOT EXISTS idx_probe_result_ts ON probe_result(ts DESC);
