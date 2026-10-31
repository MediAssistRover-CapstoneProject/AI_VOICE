package com.mediassistrover.backend.controller.pi;
import com.mediassistrover.backend.assistant.*;import com.mediassistrover.backend.assistant.AssistantDtos.*;import com.mediassistrover.backend.security.DevicePrincipal;
import lombok.RequiredArgsConstructor;import org.springframework.http.*;import org.springframework.security.access.prepost.PreAuthorize;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.web.bind.annotation.*;import org.springframework.web.multipart.MultipartFile;import java.util.UUID;
@RestController @RequestMapping("/api/v1/pi/assistant") @RequiredArgsConstructor @PreAuthorize("hasRole('DEVICE')")
public class PiAssistantController {private final AssistantService service;
 @PostMapping("/sessions") @ResponseStatus(HttpStatus.CREATED) public SessionResponse session(@RequestParam(defaultValue="vi-VN")String language,@AuthenticationPrincipal DevicePrincipal d){return service.createDeviceSession(language,d);}
 @PostMapping(value="/sessions/{id}/turns",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public TurnResponse audio(@PathVariable UUID id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestPart("audio")MultipartFile f,@AuthenticationPrincipal DevicePrincipal d)throws Exception{return service.audioTurn(id,f.getBytes(),f.getOriginalFilename(),key,"PI",d);}
 @PostMapping("/sessions/{id}/turns") public TurnResponse text(@PathVariable UUID id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody TextTurnRequest r,@AuthenticationPrincipal DevicePrincipal d){return service.textTurn(id,r.text(),key,"PI",d);}
 @GetMapping("/turns/{id}") public TurnResponse get(@PathVariable UUID id,@AuthenticationPrincipal DevicePrincipal d){return service.getTurn(id,"PI",d);}
 @PostMapping("/turns/{id}/confirm") public TurnResponse confirm(@PathVariable UUID id,@RequestBody ConfirmRequest r,@AuthenticationPrincipal DevicePrincipal d){return service.confirm(id,r.nonce(),"PI",d);}
 @GetMapping(value="/turns/{id}/audio",produces="audio/wav") public ResponseEntity<byte[]> audio(@PathVariable UUID id,@AuthenticationPrincipal DevicePrincipal d){TurnResponse t=service.getTurn(id,"PI",d);return ResponseEntity.ok().header("X-Audio-SHA256",t.audioSha256()==null?"":t.audioSha256()).body(service.audio(id,"PI",d));}
}
