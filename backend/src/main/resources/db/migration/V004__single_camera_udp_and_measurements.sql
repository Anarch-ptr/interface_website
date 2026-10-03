CREATE TABLE raspberry_pi_udp_input_revisions (
    revision INTEGER PRIMARY KEY AUTOINCREMENT,
    payload BLOB NOT NULL
);
CREATE TABLE raspberry_pi_udp_input_state (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    saved_revision INTEGER NOT NULL REFERENCES raspberry_pi_udp_input_revisions(revision),
    applied_revision INTEGER NOT NULL REFERENCES raspberry_pi_udp_input_revisions(revision)
);
CREATE TABLE measurements_v2 (
    id TEXT PRIMARY KEY NOT NULL,
    session_id TEXT NOT NULL REFERENCES sessions(id),
    timestamp INTEGER NOT NULL,
    applied_revision INTEGER REFERENCES configuration_revisions(revision),
    diameter_mm REAL NOT NULL,
    confidence REAL NOT NULL,
    status TEXT NOT NULL,
    source_id TEXT NOT NULL,
    pixel_width REAL,
    source_timestamp INTEGER NOT NULL,
    source_sequence INTEGER NOT NULL,
    valid INTEGER NOT NULL,
    roi_left INTEGER NOT NULL,
    roi_top INTEGER NOT NULL,
    roi_right INTEGER NOT NULL,
    roi_bottom INTEGER NOT NULL,
    left_edge INTEGER,
    right_edge INTEGER,
    error TEXT,
    source TEXT NOT NULL
);
CREATE INDEX measurement_v2_history ON measurements_v2(session_id, timestamp, id);
