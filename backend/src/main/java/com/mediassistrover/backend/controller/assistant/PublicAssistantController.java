package com.mediassistrover.backend.controller.assistant;
import com.mediassistrover.backend.assistant.*;import com.mediassistrover.backend.assistant.AssistantDtos.*;
import jakarta.validation.Valid;import lombok.RequiredArgsConstructor;import org.springframework.http.*;import org.springframework.web.bind.annotation.*;import org.springframework.web.multipart.MultipartFile;import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.UUID;
@RestController @RequestMapping("/api/v1/public/assistant") @RequiredArgsConstructor
public class PublicAssistantController {
 private final AssistantService service;
 @PostMapping("/sessions") @ResponseStatus(HttpStatus.CREATED) public SessionResponse session(@RequestBody CreateSessionRequest r){return service.createPublicSession(r);}
 @PostMapping("/sessions/{id}/turns") public TurnResponse text(@PathVariable UUID id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody TextTurnRequest r){return service.textTurn(id,r.text(),key,"KIOSK",null);}
 @PostMapping(value="/sessions/{id}/turns",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public TurnResponse audio(@PathVariable UUID id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestPart("audio") MultipartFile file)throws Exception{return service.audioTurn(id,file.getBytes(),file.getOriginalFilename(),key,"KIOSK",null);}
 @GetMapping("/turns/{id}") public TurnResponse get(@PathVariable UUID id){return service.getTurn(id,"KIOSK",null);}
 @GetMapping(value="/turns/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter events(@PathVariable UUID id)throws Exception{SseEmitter e=new SseEmitter(5000L);e.send(SseEmitter.event().name("turn").data(service.getTurn(id,"KIOSK",null)));e.complete();return e;}
 @PostMapping("/turns/{id}/confirm") public TurnResponse confirm(@PathVariable UUID id,@RequestBody ConfirmRequest r){return service.confirm(id,r.nonce(),"KIOSK",null);}
 @GetMapping(value="/turns/{id}/audio",produces="audio/wav") public ResponseEntity<byte[]> audio(@PathVariable UUID id){TurnResponse t=service.getTurn(id,"KIOSK",null);return ResponseEntity.ok().header("X-Audio-SHA256",t.audioSha256()==null?"":t.audioSha256()).header(HttpHeaders.CACHE_CONTROL,"private, max-age=3600").body(service.audio(id,"KIOSK",null));}
}
