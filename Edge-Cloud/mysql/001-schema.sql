USE powershare;

CREATE TABLE IF NOT EXISTS iot_history (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  received_at DATETIME(3) NOT NULL COMMENT 'Hora UTC de recepcion en Node-RED',
  device_prefix VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_topic VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  command_id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  boot_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  payload JSON NOT NULL,
  PRIMARY KEY (id),
  KEY idx_device_time (device_prefix, received_at, id),
  KEY idx_kind_time (device_prefix, kind, received_at, id),
  KEY idx_command (device_prefix, command_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
