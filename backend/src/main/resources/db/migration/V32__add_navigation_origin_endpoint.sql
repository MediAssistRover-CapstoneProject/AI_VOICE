ALTER TABLE `NavigationRequest`
    ADD COLUMN `origin_endpoint_id` BINARY(16) NULL AFTER `room_id`,
    ADD CONSTRAINT `fk_navigation_request_origin_endpoint`
        FOREIGN KEY (`origin_endpoint_id`) REFERENCES `Endpoint` (`endpoint_id`);

CREATE INDEX `idx_navigation_request_origin_endpoint`
    ON `NavigationRequest` (`origin_endpoint_id`);
