package com.mediassistrover.backend.controller.assistant;

import com.mediassistrover.backend.assistant.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin/assistant/voice")
@RequiredArgsConstructor @PreAuthorize("hasRole('ADMIN')")
public class VoiceAdminController {
    private final VoiceSettingsService settings;
    private final OpenAiGateway gateway;
    @GetMapping public ResponseEntity<VoiceSettingsService.Settings> get(){ return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.current()); }
    @PutMapping public VoiceSettingsService.Settings save(@RequestBody VoiceSettingsService.Settings body){ return settings.save(body); }
    @PostMapping(value="/preview", produces="audio/wav")
    public ResponseEntity<byte[]> preview(@RequestBody VoiceSettingsService.Settings body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(gateway.synthesizeWav(
            "Xin chào, tôi là MAR. Tôi có thể giúp bạn tra cứu thông tin bệnh viện. Bạn cần hỗ trợ gì?", "vi-VN", settings.validate(body)));
    }
}
