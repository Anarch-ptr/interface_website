ALTER TABLE snapshots ADD COLUMN source_sequence INTEGER CHECK (source_sequence IS NULL OR source_sequence >= 0);
