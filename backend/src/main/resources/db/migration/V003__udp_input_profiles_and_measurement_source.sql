CREATE TABLE udp_input_revisions (
    revision INTEGER PRIMARY KEY AUTOINCREMENT,
    payload BLOB NOT NULL
);
CREATE TABLE udp_input_state (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    applied_revision INTEGER REFERENCES udp_input_revisions(revision)
);
INSERT INTO udp_input_state(singleton, applied_revision) VALUES (1, NULL);
ALTER TABLE measurements ADD COLUMN a_source TEXT DEFAULT 'VISION';
ALTER TABLE measurements ADD COLUMN b_source TEXT DEFAULT 'VISION';
