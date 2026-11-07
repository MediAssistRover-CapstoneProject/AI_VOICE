package com.mediassistrover.backend.controller.assistant;
import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.assistant.VoiceServiceGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/admin/assistant/faqs")
@RequiredArgsConstructor @PreAuthorize("hasRole('ADMIN')")
public class AssistantFaqController {
    private final VoiceServiceGateway voice;
    @GetMapping public ResponseEntity<JsonNode> list(){return voice.forward("GET","/api/faqs",null);}
    @PostMapping public ResponseEntity<JsonNode> create(@RequestBody JsonNode body){return voice.forward("POST","/api/faqs",body);}
    @PutMapping("/{id}") public ResponseEntity<JsonNode> update(@PathVariable UUID id,@RequestBody JsonNode body){return voice.forward("PUT","/api/faqs/"+id,body);}
    @DeleteMapping("/{id}") public ResponseEntity<JsonNode> delete(@PathVariable UUID id,@RequestParam String revision){
        if(!revision.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid FAQ revision");
        return voice.forward("DELETE","/api/faqs/"+id+"?revision="+revision,null);
    }
    @PostMapping("/test") public ResponseEntity<JsonNode> test(@RequestBody JsonNode body){return voice.forward("POST","/api/faqs/test",body);}
}
