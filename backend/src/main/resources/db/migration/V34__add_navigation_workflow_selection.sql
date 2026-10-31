ALTER TABLE `NavigationRequest`
    ADD COLUMN `workflow_id` BINARY(16) NULL AFTER `map_id`,
    ADD CONSTRAINT `fk_navigation_request_workflow`
        FOREIGN KEY (`workflow_id`) REFERENCES `Workflow` (`workflow_id`);

CREATE INDEX `idx_navigation_request_workflow`
    ON `NavigationRequest` (`workflow_id`);
