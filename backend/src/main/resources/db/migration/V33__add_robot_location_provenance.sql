ALTER TABLE `RobotLocationHistory`
    ADD COLUMN `frame_id` VARCHAR(80) NULL AFTER `localization_confidence`,
    ADD COLUMN `source` VARCHAR(80) NULL AFTER `frame_id`;
