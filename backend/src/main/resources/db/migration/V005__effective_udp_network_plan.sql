ALTER TABLE raspberry_pi_udp_input_state ADD COLUMN applied_network_plan BLOB;
ALTER TABLE raspberry_pi_udp_input_state ADD COLUMN resolution_generation INTEGER NOT NULL DEFAULT 0 CHECK (resolution_generation >= 0);
