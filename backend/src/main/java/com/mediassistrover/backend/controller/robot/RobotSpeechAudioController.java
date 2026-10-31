package com.mediassistrover.backend.controller.robot;

import com.mediassistrover.backend.speech.OpenAiTtsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/v1/pi/speech") @RequiredArgsConstructor
@PreAuthorize("hasAnyRole('DEVICE','ADMIN')")
@Tag(name="Robot Speech",description="Vietnamese TTS audio prepared on BE; API key never goes to Pi")
public class RobotSpeechAudioController {
    private final OpenAiTtsService speech;
    public record Request(@NotBlank @Size(min=1,max=500) String text,
                          @NotNull @Pattern(regexp="vi|en") String languageCode,
                          @Min(80) @Max(220) Integer speedWpm) { }
    @PostMapping("/prepare") @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary="Prepare/cache a phrase before sending a speech Job; repeat until state READY")
    public ResponseEntity<?> prepare(@Valid @RequestBody Request input) {
        var result=speech.request(input.text(),input.languageCode(),input.speedWpm()==null?150:input.speedWpm());
        return ResponseEntity.status(result.state().equals("FAILED")?503:200)
            .header("Cache-Control","no-store")
            .body(Map.of("state",result.state(),"code",result.code()==null?
                (result.audio()==null?"TTS_GENERATING":"TTS_AUDIO_READY"):result.code()));
    }
    @PostMapping("/synthesize")
    @Operation(summary="Prepare Vietnamese audio: 202 generating, 200 WAV ready, 503 provider/configuration error")
    public ResponseEntity<?> synthesize(@Valid @RequestBody Request input) {
        var result=speech.request(input.text(),input.languageCode(),input.speedWpm()==null?150:input.speedWpm());
        if(result.audio()!=null)return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav"))
            .header("X-Audio-SHA256",OpenAiTtsService.sha256(result.audio())).header("X-TTS-Provider","openai")
            .header("Cache-Control","no-store").body(result.audio());
        return ResponseEntity.status(result.state().equals("PENDING")?202:503)
            .header("Retry-After","2").header("Cache-Control","no-store")
            .body(Map.of("state",result.state(),"code",result.code()==null?"TTS_GENERATING":result.code()));
    }
}
