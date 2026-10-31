package com.mediassistrover.backend.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.config.AssistantProperties;
import com.mediassistrover.backend.entity.KnowledgeChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Component @RequiredArgsConstructor
public class QdrantKnowledgeStore {
    public record Hit(UUID chunkId,UUID articleId,double score) { }
    private final AssistantProperties properties; private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public void upsert(List<KnowledgeChunk> chunks,List<float[]> vectors){
        if(chunks.size()!=vectors.size())throw new IllegalArgumentException("Chunk/vector count mismatch");ensureCollection();
        ObjectNode body=json.createObjectNode();ArrayNode points=body.putArray("points");
        for(int i=0;i<chunks.size();i++){
            KnowledgeChunk c=chunks.get(i);ObjectNode p=points.addObject().put("id",c.getQdrantPointId());ArrayNode v=p.putArray("vector");for(float x:vectors.get(i))v.add(x);
            var d=c.getVersion().getDocument();ObjectNode payload=p.putObject("payload");payload.put("chunk_id",c.getChunkId().toString()).put("version_id",c.getVersion().getVersionId().toString()).put("document_id",d.getDocumentId().toString()).put("visibility",d.getVisibility()).put("language",d.getLanguageCode()).put("title",d.getTitle());
        }
        send("PUT","/collections/"+properties.getQdrantCollection()+"/points?wait=true",body);
    }
    public void upsertArticles(List<com.mediassistrover.backend.entity.HospitalInformationArticle> articles,List<float[]> vectors){
        if(articles.size()!=vectors.size())throw new IllegalArgumentException("Article/vector count mismatch");ensureCollection();ObjectNode body=json.createObjectNode();ArrayNode points=body.putArray("points");
        for(int i=0;i<articles.size();i++){var a=articles.get(i);boolean publicScope=a.isPublicVisible()&&a.getHospitalInformation().isPublicVisible();ObjectNode p=points.addObject().put("id",a.getArticleId().toString());ArrayNode v=p.putArray("vector");for(float x:vectors.get(i))v.add(x);p.putObject("payload").put("source_type","ARTICLE").put("article_id",a.getArticleId().toString()).put("visibility",publicScope?"PUBLIC":"INTERNAL").put("language",a.getLanguageCode()).put("title",a.getTitle());}
        send("PUT","/collections/"+properties.getQdrantCollection()+"/points?wait=true",body);
    }
    public List<Hit> search(float[] vector,boolean internal){ensureCollection();ObjectNode body=json.createObjectNode().put("limit",12).put("with_payload",true).put("score_threshold",0.18);ArrayNode v=body.putArray("vector");for(float x:vector)v.add(x);if(!internal)body.putObject("filter").putArray("must").addObject().put("key","visibility").putObject("match").put("value","PUBLIC");JsonNode response=send("POST","/collections/"+properties.getQdrantCollection()+"/points/search",body);List<Hit> hits=new ArrayList<>();for(JsonNode item:response.path("result")){try{JsonNode p=item.path("payload");UUID chunk=p.hasNonNull("chunk_id")?UUID.fromString(p.path("chunk_id").asText()):null;UUID article=p.hasNonNull("article_id")?UUID.fromString(p.path("article_id").asText()):null;if(chunk!=null||article!=null)hits.add(new Hit(chunk,article,item.path("score").asDouble()));}catch(Exception ignored){}}return hits;}
    public void deleteVersion(UUID versionId){ObjectNode selector=json.createObjectNode();selector.putObject("filter").putArray("must").addObject().put("key","version_id").putObject("match").put("value",versionId.toString());send("POST","/collections/"+properties.getQdrantCollection()+"/points/delete?wait=true",selector);}
    public boolean healthy(){try{HttpRequest r=HttpRequest.newBuilder(URI.create(base()+"/healthz")).timeout(Duration.ofSeconds(2)).GET().build();return http.send(r,HttpResponse.BodyHandlers.discarding()).statusCode()==200;}catch(Exception e){return false;}}
    private void ensureCollection(){try{request("GET","/collections/"+properties.getQdrantCollection(),null);}catch(NotFound e){ObjectNode b=json.createObjectNode();b.putObject("vectors").put("size",1536).put("distance","Cosine");send("PUT","/collections/"+properties.getQdrantCollection(),b);}}
    private JsonNode send(String method,String path,JsonNode body){try{return json.readTree(request(method,path,body));}catch(java.io.IOException e){throw new IllegalStateException("QDRANT_INVALID_RESPONSE",e);}}
    private byte[] request(String method,String path,JsonNode body){try{HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");byte[] bytes=body==null?new byte[0]:json.writeValueAsBytes(body);b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes));HttpResponse<byte[]> r=http.send(b.build(),HttpResponse.BodyHandlers.ofByteArray());if(r.statusCode()==404)throw new NotFound();if(r.statusCode()<200||r.statusCode()>=300)throw new IllegalStateException("QDRANT_HTTP_"+r.statusCode());return r.body();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("QDRANT_INTERRUPTED",e);}catch(java.io.IOException e){throw new IllegalStateException("QDRANT_UNAVAILABLE",e);}}
    private String base(){return properties.getQdrantUrl().replaceAll("/$","");}private static class NotFound extends RuntimeException{}
}
