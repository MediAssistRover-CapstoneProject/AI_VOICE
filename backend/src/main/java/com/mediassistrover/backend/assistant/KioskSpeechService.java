package com.mediassistrover.backend.assistant;

import com.mediassistrover.backend.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Opt-in cloud playback. Session access is checked even when the WAV is cached. */
@Service
@RequiredArgsConstructor
public class KioskSpeechService {
    public static final String GREETING = "Tôi là MAR. Tôi có thể giúp bạn tra cứu thông tin bệnh viện. Bạn cần hỗ trợ gì?";
    public static final String OUT_OF_SCOPE = "Câu hỏi này nằm ngoài phạm vi hỗ trợ của tôi. Tôi chỉ hỗ trợ thông tin có trong tài liệu bệnh viện.";
    private static final int MAX_ENTRIES = 64;
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private final AssistantService assistant;
    private final VoiceSettingsService settings;
    private final OpenAiGateway gateway;
    private final Map<CacheKey, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cachedBytes;

    private record CacheKey(String text, VoiceSettingsService.Settings settings) {}

    public byte[] synthesize(UUID sessionId, UUID turnId) {
        String text = assistant.kioskSpeechText(sessionId, turnId);
        VoiceSettingsService.Settings voice = settings.current();
        if (!"CLOUD".equals(voice.kioskEngine()))
            throw new BusinessRuleException("Kiosk cloud voice is disabled");
        CacheKey key = new CacheKey(text, voice);
        synchronized (cache) {
            byte[] hit = cache.get(key);
            if (hit != null) return hit.clone();
        }

        // Provider work never holds the cache lock. A new revision always uses a new key.
        byte[] wav = gateway.synthesizeWav(text, "vi-VN", voice);
        if (wav.length <= MAX_BYTES) {
            synchronized (cache) {
                byte[] previous = cache.put(key, wav.clone());
                cachedBytes += wav.length - (previous == null ? 0 : previous.length);
                while (cache.size() > MAX_ENTRIES || cachedBytes > MAX_BYTES) {
                    var oldest = cache.entrySet().iterator();
                    cachedBytes -= oldest.next().getValue().length;
                    oldest.remove();
                }
            }
        }
        return wav;
    }
}
