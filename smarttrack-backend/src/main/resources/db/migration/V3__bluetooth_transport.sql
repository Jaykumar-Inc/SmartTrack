-- Preserve earlier real and demo history while recording the new transport explicitly.
ALTER TABLE sensor_readings ADD COLUMN transport VARCHAR(24) NOT NULL DEFAULT 'LEGACY';
