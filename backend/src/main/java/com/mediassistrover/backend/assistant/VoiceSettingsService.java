package com.mediassistrover.backend.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;

@Service @RequiredArgsConstructor
public class VoiceSettingsService {
    public record Settings(String profile, String cloudVoice, String style, double rate, String browserVoiceUri, String kioskEngine, long revision) {
        public Settings {
            // Rows created before the playback selector used the browser voice.
            kioskEngine = kioskEngine == null ? "BROWSER" : kioskEngine;
        }
        public Settings(String profile, String cloudVoice, String style, double rate, String browserVoiceUri, long revision) {
            this(profile, cloudVoice, style, rate, browserVoiceUri, "BROWSER", revision);
        }
        public String instructions() {
            return switch (style) {
                case "WARM" -> "Use a warm, friendly and reassuring tone.";
                case "FORMAL" -> "Use a professional, clear and formal tone.";
                default -> "Use a calm and clear tone.";
            };
        }
    }
    public static final Set<String> VOICES = Set.of("alloy","ash","ballad","coral","echo","sage","shimmer","verse","marin","cedar");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public Settings current() {
        return jdbc.queryForObject("SELECT config_json, revision FROM AiVoiceSettings WHERE id=1", (rs, row) -> {
            try {
                Settings s = json.readValue(rs.getString(1), Settings.class);
                return new Settings(s.profile(),s.cloudVoice(),s.style(),s.rate(),s.browserVoiceUri(),s.kioskEngine(),rs.getLong(2));
            } catch (Exception e) { throw new IllegalStateException("Invalid stored voice settings", e); }
        });
    }
    public Settings validate(Settings s) {
        if (s == null || s.profile()==null || !Set.of("MALE","FEMALE","CUSTOM").contains(s.profile())
            || s.cloudVoice()==null || !VOICES.contains(s.cloudVoice())
            || s.style()==null || !Set.of("CALM","WARM","FORMAL").contains(s.style())
            || !Double.isFinite(s.rate()) || s.rate()<0.75 || s.rate()>1.5
            || s.browserVoiceUri()==null || s.browserVoiceUri().length()>500
            || !Set.of("BROWSER","CLOUD").contains(s.kioskEngine()) || s.revision()<0)
            throw new BusinessRuleException("Cấu hình giọng nói không hợp lệ. Tốc độ phải từ 0.75 đến 1.5.");
        return s;
    }
    @Transactional
    public Settings save(Settings s) {
        validate(s);
        try {
            int count = jdbc.update("UPDATE AiVoiceSettings SET config_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=1 AND revision=?", json.writeValueAsString(s), s.revision());
            if(count!=1) throw new BusinessRuleException("Cấu hình đã được sửa ở nơi khác. Hãy tải lại trước khi lưu.");
            return current();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
