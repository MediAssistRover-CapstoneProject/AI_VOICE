package com.mediassistrover.backend.assistant;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mediassistrover.backend.assistant.KnowledgeRetrievalService.Source;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class VoiceServiceGateway {
    private final ObjectMapper json;
    private final boolean enabled;
    private final String base,token;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public record Answer(JsonNode result,List<Source> sources,String model){}
    public VoiceServiceGateway(ObjectMapper json,
        @Value("${VOICE_SERVICE_ENABLED:false}") boolean enabled,
        @Value("${VOICE_SERVICE_URL:http://voice:8000}") String base,
        @Value("${VOICE_SERVICE_TOKEN:}") String token){
        this.json=json;this.enabled=enabled;this.base=base.replaceAll("/+$","");this.token=token;
        if(enabled&&token.isBlank())throw new IllegalStateException("VOICE_SERVICE_TOKEN_REQUIRED");
    }
    public ResponseEntity<JsonNode> forward(String method,String path,JsonNode body){
        if(!enabled)return ResponseEntity.status(503).body(json.createObjectNode().put("detail","VOICE_SERVICE_DISABLED"));
        try{
            var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(35))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.body().length()>8*1024*1024)throw new IllegalStateException("VOICE_RESPONSE_TOO_LARGE");
            return ResponseEntity.status(response.statusCode()).cacheControl(CacheControl.noStore()).body(json.readTree(response.body()));
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("VOICE_SERVICE_UNAVAILABLE");}
        catch(Exception e){throw new IllegalStateException("VOICE_SERVICE_UNAVAILABLE");}
    }
    public Optional<Answer> answer(String text,List<Map<String,String>> history,boolean faqOnly,boolean internal){
        if(!enabled)return Optional.empty();
        ObjectNode body=json.createObjectNode().put("text",text).put("faq_only",faqOnly).put("internal",internal);
        body.set("history",json.valueToTree(history));
        var response=forward("POST","/internal/answer",body);
        if(!response.getStatusCode().is2xxSuccessful()||response.getBody()==null)throw new IllegalStateException("VOICE_SERVICE_UNAVAILABLE");
        JsonNode value=response.getBody();if(!value.path("handled").asBoolean())return Optional.empty();
        String answer=value.path("answer").asText();if(answer.isBlank()||answer.length()>4000)throw new IllegalStateException("VOICE_RESPONSE_INVALID");
        List<Source> sources=new ArrayList<>();
        for(JsonNode source:value.path("sources")){
            String title=source.path("source").asText("");if(title.isBlank())continue;
            title=title.substring(0,Math.min(255,title.length()));
            String id="VOICE"+(sources.size()+1);
            sources.add(new Source(id,"DOCUMENT",null,null,null,title,"local:"+source.path("faq_id").asText(id),
                value.path("context").asText(answer),digest(answer),Math.max(0,Math.min(1,source.path("score").asDouble(1))),null));
            if(sources.size()==5)break;
        }
        ObjectNode result=json.createObjectNode().put("intent","INFORMATION").put("answer",answer).put("speech_text",answer)
            .put("language","vi-VN").put("grounding_mode",sources.isEmpty()?"GENERAL":"HOSPITAL");
        ArrayNode ids=result.putArray("used_source_ids");sources.forEach(s->ids.add(s.sourceId()));
        result.putArray("warnings");
        return Optional.of(new Answer(result,sources,value.path("model").isNull()?null:value.path("model").asText(null)));
    }
    private String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
