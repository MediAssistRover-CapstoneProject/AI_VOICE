CREATE TABLE RobotNavigationSession (
    robot_id BINARY(16) NOT NULL PRIMARY KEY,
    session_id BINARY(16) NOT NULL UNIQUE,
    map_id BINARY(16) NOT NULL,
    map_asset_hash CHAR(64) NOT NULL,
    producer_session_id BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    closed_at DATETIME(6) NULL,
    initialization_mode VARCHAR(16) NOT NULL,
    initialization_revision BIGINT NOT NULL,
    initial_pose_json JSON NULL,
    CONSTRAINT fk_navigation_session_robot FOREIGN KEY (robot_id) REFERENCES Robot(robot_id),
    CONSTRAINT fk_navigation_session_map FOREIGN KEY (map_id) REFERENCES Map(map_id)
);
