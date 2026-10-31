-- Transport selection is immutable on Job. This latch is independent of app.mqtt.enabled.
CREATE TABLE `MqttRolloutControl` (
 `control_id` INT NOT NULL, `mode` VARCHAR(24) NOT NULL,
 `dispatch_halted` BOOLEAN NOT NULL, `revision` BIGINT NOT NULL,
 `updated_by` BINARY(16) NULL, `updated_at` DATETIME(6) NOT NULL, `note` VARCHAR(1000) NOT NULL,
 PRIMARY KEY (`control_id`),
 CONSTRAINT `ck_mqtt_rollout_singleton` CHECK (`control_id` = 1),
 CONSTRAINT `ck_mqtt_rollout_mode` CHECK (`mode` IN ('LEGACY_COMPATIBLE','DRAINING','MQTT_ONLY')),
 CONSTRAINT `ck_mqtt_rollout_revision` CHECK (`revision` >= 0)
) ENGINE=InnoDB;
INSERT INTO `MqttRolloutControl` VALUES (1,'LEGACY_COMPATIBLE',FALSE,0,NULL,UTC_TIMESTAMP(6),'Initial compatibility mode; no production cutover performed');
CREATE TABLE `MqttRolloutAudit` (
 `revision` BIGINT NOT NULL, `mode` VARCHAR(24) NOT NULL, `dispatch_halted` BOOLEAN NOT NULL,
 `actor_id` BINARY(16) NOT NULL, `recorded_at` DATETIME(6) NOT NULL, `note` VARCHAR(1000) NOT NULL,
 PRIMARY KEY (`revision`)
) ENGINE=InnoDB;
