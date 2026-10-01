CREATE TABLE configuration_revisions (
    revision INTEGER PRIMARY KEY AUTOINCREMENT,
    payload BLOB NOT NULL
);
CREATE TABLE configuration_state (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    applied_revision INTEGER REFERENCES configuration_revisions(revision)
);
INSERT INTO configuration_state(singleton, applied_revision) VALUES (1, NULL);
CREATE TABLE sessions (
    id TEXT PRIMARY KEY NOT NULL,
    generation INTEGER NOT NULL,
    started_at INTEGER NOT NULL,
    ended_at INTEGER, outcome TEXT, error TEXT,
    sample_count INTEGER, average_diameter REAL, minimum_diameter REAL, maximum_diameter REAL,
    standard_deviation REAL, average_confidence REAL, abnormal_event_count INTEGER
);
CREATE INDEX session_history ON sessions(started_at, id);
CREATE TABLE measurements (
    id TEXT PRIMARY KEY NOT NULL,
    session_id TEXT NOT NULL REFERENCES sessions(id),
    timestamp INTEGER NOT NULL,
    applied_revision INTEGER REFERENCES configuration_revisions(revision),
    fused_diameter REAL NOT NULL, shape_difference REAL NOT NULL, confidence REAL NOT NULL,
    status TEXT NOT NULL, fusion_reason TEXT NOT NULL,
    a_id TEXT, a_pixel_width REAL, a_diameter REAL, a_confidence REAL, a_timestamp INTEGER, a_sequence INTEGER, a_valid INTEGER, a_roi_left INTEGER, a_roi_top INTEGER, a_roi_right INTEGER, a_roi_bottom INTEGER, a_left_edge INTEGER, a_right_edge INTEGER, a_error TEXT,
    b_id TEXT, b_pixel_width REAL, b_diameter REAL, b_confidence REAL, b_timestamp INTEGER, b_sequence INTEGER, b_valid INTEGER, b_roi_left INTEGER, b_roi_top INTEGER, b_roi_right INTEGER, b_roi_bottom INTEGER, b_left_edge INTEGER, b_right_edge INTEGER, b_error TEXT
);
CREATE INDEX measurement_history ON measurements(session_id, timestamp, id);
CREATE TABLE alarms (
    id TEXT PRIMARY KEY NOT NULL, session_id TEXT NOT NULL REFERENCES sessions(id),
    timestamp INTEGER NOT NULL, fused_diameter REAL NOT NULL,
    from_level TEXT NOT NULL, from_direction TEXT NOT NULL, from_deviation REAL NOT NULL,
    to_level TEXT NOT NULL, to_direction TEXT NOT NULL, to_deviation REAL NOT NULL
);
CREATE INDEX alarm_history ON alarms(session_id, timestamp, id);
CREATE TABLE snapshots (
    id TEXT PRIMARY KEY NOT NULL, session_id TEXT NOT NULL REFERENCES sessions(id),
    timestamp INTEGER NOT NULL, camera_id TEXT NOT NULL, object_key TEXT NOT NULL
);
CREATE INDEX snapshot_history ON snapshots(session_id, timestamp, id);
CREATE TABLE errors (
    id TEXT PRIMARY KEY NOT NULL, session_id TEXT REFERENCES sessions(id),
    timestamp INTEGER NOT NULL, code TEXT NOT NULL, message TEXT NOT NULL
);
CREATE INDEX error_history ON errors(session_id, timestamp, id);
