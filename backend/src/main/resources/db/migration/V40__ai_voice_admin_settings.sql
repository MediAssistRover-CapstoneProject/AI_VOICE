CREATE TABLE AiVoiceSettings (
    id INT NOT NULL PRIMARY KEY,
    config_json LONGTEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
INSERT INTO AiVoiceSettings(id, config_json) VALUES (1,
 '{"profile":"FEMALE","cloudVoice":"coral","style":"CALM","rate":1.0,"browserVoiceUri":""}');
