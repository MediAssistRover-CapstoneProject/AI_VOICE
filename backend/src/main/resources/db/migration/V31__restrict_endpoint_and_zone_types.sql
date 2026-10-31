-- Fail before persistent DDL if either table still contains legacy values.
-- The temporary table is session-scoped and no application data is changed.
CREATE TEMPORARY TABLE `_v31_type_preflight` (
    `valid` TINYINT NOT NULL,
    CONSTRAINT `CK_v31_type_preflight` CHECK (`valid` = 1)
);

INSERT INTO `_v31_type_preflight` (`valid`)
SELECT 0
WHERE EXISTS (
    SELECT 1 FROM `Endpoint`
    WHERE `endpoint_type` NOT IN ('WAYPOINT', 'CHARGER', 'PARK', 'STAGING')
) OR EXISTS (
    SELECT 1 FROM `Zone`
    WHERE `zone_type` NOT IN ('KEEP_OUT', 'SPEED', 'NO_STOP')
);

DROP TEMPORARY TABLE `_v31_type_preflight`;

ALTER TABLE `Endpoint`
    DROP CHECK `CK_Endpoint_type`,
    ADD CONSTRAINT `CK_Endpoint_type_v31`
        CHECK (`endpoint_type` IN ('WAYPOINT', 'CHARGER', 'PARK', 'STAGING'));

ALTER TABLE `Zone`
    DROP CHECK `CK_Zone_type`,
    ADD CONSTRAINT `CK_Zone_type_v31`
        CHECK (`zone_type` IN ('KEEP_OUT', 'SPEED', 'NO_STOP'));
