package com.mediassistrover.backend.assistant;

import com.mediassistrover.backend.config.AssistantProperties;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.HospitalInformationStatus;
import com.mediassistrover.backend.knowledge.QdrantKnowledgeStore;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.HospitalInformationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class KnowledgeRetrievalService {
    private static final double RRF_K=60.0;
    public record Source(String sourceId,String type,UUID documentId,UUID articleId,UUID chunkId,String title,String locator,String content,String contentHash,double score,Instant effectiveAt){}
    private record Candidate(Source source,Integer vectorRank,Integer keywordRank){}

    private final OpenAiGateway openAi;private final QdrantKnowledgeStore qdrant;private final KnowledgeChunkRepository chunks;private final HospitalInformationQueryService hospitalQuery;private final HospitalInformationArticleRepository articles;private final AssistantProperties properties;private final Clock clock;

    public List<Source> retrieve(String question,String language,boolean internal){
        Map<String,Candidate> candidates=new LinkedHashMap<>();
        try{float[] vector=openAi.embeddings(List.of(question)).getFirst();int rank=1;for(QdrantKnowledgeStore.Hit hit:qdrant.search(vector,internal)){Source source=source(hit,internal);if(source!=null){String key=key(source);Candidate previous=candidates.get(key);candidates.put(key,new Candidate(source,rank,previous==null?null:previous.keywordRank()));}rank++;}}
        catch(RuntimeException ignored){}
        if(properties.isHybridRetrievalEnabled()||candidates.isEmpty())try{int rank=1;for(KnowledgeChunk chunk:chunks.keywordSearch(question,internal)){if(eligible(chunk,internal)){Source source=source(chunk,0);String key=key(source);Candidate previous=candidates.get(key);candidates.put(key,new Candidate(source,previous==null?null:previous.vectorRank(),rank));}rank++;}}
        catch(RuntimeException ignored){}
        try{HospitalInformationQueryService.InformationAnswer answer=hospitalQuery.query(question,language);if(answer.found()&&answer.source()!=null){HospitalInformationArticle article=articles.findById(answer.source().articleId()).orElse(null);if(article!=null&&eligible(article,internal)){Source source=source(article,0.80);candidates.put(key(source),new Candidate(source,1,1));}}}
        catch(RuntimeException ignored){}

        String normalizedQuestion=normalize(question);Map<String,Source> deduplicated=new LinkedHashMap<>();Map<UUID,Integer> perDocument=new HashMap<>();
        List<Source> ranked=candidates.values().stream().map(c->score(c,normalizedQuestion)).sorted(Comparator.comparingDouble(Source::score).reversed()).toList();
        for(Source source:ranked){String hash=source.contentHash();if(deduplicated.containsKey(hash))continue;if(source.documentId()!=null&&perDocument.merge(source.documentId(),1,Integer::sum)>4)continue;deduplicated.put(hash,source);if(deduplicated.size()>=12)break;}
        int i=1;List<Source> result=new ArrayList<>();for(Source source:deduplicated.values())result.add(new Source("S"+(i++),source.type(),source.documentId(),source.articleId(),source.chunkId(),source.title(),source.locator(),source.content(),source.contentHash(),source.score(),source.effectiveAt()));return result;
    }
    private Source score(Candidate candidate,String question){Source s=candidate.source();double rrf=0;if(candidate.vectorRank()!=null)rrf+=0.65/(RRF_K+candidate.vectorRank());if(candidate.keywordRank()!=null)rrf+=0.35/(RRF_K+candidate.keywordRank());double score=rrf/(1.0/(RRF_K+1));String content=normalize(s.content());if(!question.isBlank()&&content.contains(question))score+=0.12;Set<String> queryTokens=tokens(question);Set<String> sourceTokens=tokens(normalize(s.title()+" "+s.locator()+" "+s.content()));long overlap=queryTokens.stream().filter(sourceTokens::contains).count();if(!queryTokens.isEmpty())score+=Math.min(0.10,0.10*overlap/queryTokens.size());return new Source(null,s.type(),s.documentId(),s.articleId(),s.chunkId(),s.title(),s.locator(),s.content(),s.contentHash(),Math.min(0.999999,score),s.effectiveAt());}
    private Source source(QdrantKnowledgeStore.Hit hit,boolean internal){if(hit.chunkId()!=null){KnowledgeChunk c=chunks.findById(hit.chunkId()).orElse(null);return c==null||!eligible(c,internal)?null:source(c,hit.score());}if(hit.articleId()!=null){HospitalInformationArticle a=articles.findById(hit.articleId()).orElse(null);return a==null||!eligible(a,internal)?null:source(a,hit.score());}return null;}
    private Source source(KnowledgeChunk c,double score){KnowledgeDocument d=c.getVersion().getDocument();return new Source(null,"DOCUMENT",d.getDocumentId(),null,c.getChunkId(),d.getTitle(),locator(c),c.getContent(),c.getContentHash(),score,d.getEffectiveFrom());}
    private Source source(HospitalInformationArticle a,double score){return new Source(null,"ARTICLE",null,a.getArticleId(),null,a.getTitle(),a.getArticleCode(),a.getAnswer(),sha256(normalize(a.getAnswer())),score,a.getEffectiveFrom());}
    private String key(Source source){return source.chunkId()!=null?"D:"+source.chunkId():"A:"+source.articleId();}
    private boolean eligible(KnowledgeChunk c,boolean internal){KnowledgeDocument d=c.getVersion().getDocument();Instant now=Instant.now(clock);return "READY".equals(d.getStatus())&&d.getDuplicateOfDocument()==null&&d.getCurrentVersion()!=null&&d.getCurrentVersion().getVersionId().equals(c.getVersion().getVersionId())&&(internal||"PUBLIC".equals(d.getVisibility()))&&(d.getEffectiveFrom()==null||!d.getEffectiveFrom().isAfter(now))&&(d.getEffectiveUntil()==null||d.getEffectiveUntil().isAfter(now));}
    private boolean eligible(HospitalInformationArticle a,boolean internal){HospitalInformation h=a.getHospitalInformation();Instant now=Instant.now(clock);return a.getStatus()==HospitalInformationStatus.PUBLISHED&&h.getStatus()==HospitalInformationStatus.PUBLISHED&&(internal||a.isPublicVisible()&&h.isPublicVisible())&&(a.getEffectiveFrom()==null||!a.getEffectiveFrom().isAfter(now))&&(a.getEffectiveUntil()==null||a.getEffectiveUntil().isAfter(now))&&(h.getEffectiveFrom()==null||!h.getEffectiveFrom().isAfter(now))&&(h.getEffectiveUntil()==null||h.getEffectiveUntil().isAfter(now));}
    private String locator(KnowledgeChunk c){if(c.getLocator()!=null&&!c.getLocator().isBlank())return c.getLocator();if(c.getPageFrom()!=null)return "Trang "+c.getPageFrom();if(c.getHeading()!=null)return c.getHeading();return "Đoạn "+(c.getChunkIndex()+1);}
    private String normalize(String value){String n=Normalizer.normalize(value==null?"":value,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);return n.replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
    private Set<String> tokens(String value){Set<String> out=new HashSet<>();for(String token:value.split("\\s+"))if(token.length()>1)out.add(token);return out;}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
