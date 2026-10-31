package com.mediassistrover.backend.assistant;

import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.HospitalInformationStatus;
import com.mediassistrover.backend.knowledge.QdrantKnowledgeStore;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.HospitalInformationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class KnowledgeRetrievalService {
    public record Source(String type,UUID documentId,UUID articleId,UUID chunkId,String title,String locator,String content,double score,Instant effectiveAt){}
    private final OpenAiGateway openAi;private final QdrantKnowledgeStore qdrant;private final KnowledgeChunkRepository chunks;private final HospitalInformationQueryService hospitalQuery;private final HospitalInformationArticleRepository articles;private final Clock clock;
    public List<Source> retrieve(String question,String language,boolean internal){LinkedHashMap<String,Source> result=new LinkedHashMap<>();
        try{float[] vector=openAi.embeddings(List.of(question)).getFirst();for(QdrantKnowledgeStore.Hit hit:qdrant.search(vector,internal)){if(hit.chunkId()!=null){KnowledgeChunk c=chunks.findById(hit.chunkId()).orElse(null);if(c==null||!eligible(c,internal))continue;KnowledgeDocument d=c.getVersion().getDocument();result.put("D:"+c.getChunkId(),new Source("DOCUMENT",d.getDocumentId(),null,c.getChunkId(),d.getTitle(),locator(c),c.getContent(),hit.score(),d.getEffectiveFrom()));}else if(hit.articleId()!=null){HospitalInformationArticle a=articles.findById(hit.articleId()).orElse(null);if(a==null||!eligible(a,internal))continue;result.put("A:"+a.getArticleId(),new Source("ARTICLE",null,a.getArticleId(),null,a.getTitle(),a.getArticleCode(),a.getAnswer(),hit.score(),a.getEffectiveFrom()));}if(result.size()>=5)break;}}
        catch(RuntimeException unavailable){try{for(KnowledgeChunk c:chunks.keywordSearch(question,internal)){if(!eligible(c,internal))continue;KnowledgeDocument d=c.getVersion().getDocument();result.put("D:"+c.getChunkId(),new Source("DOCUMENT",d.getDocumentId(),null,c.getChunkId(),d.getTitle(),locator(c),c.getContent(),0.25,d.getEffectiveFrom()));if(result.size()>=5)break;}}catch(RuntimeException ignored){}}
        try{HospitalInformationQueryService.InformationAnswer answer=hospitalQuery.query(question,language);if(answer.found()&&answer.source()!=null){HospitalInformationArticle a=articles.findById(answer.source().articleId()).orElse(null);if(a!=null)result.put("A:"+a.getArticleId(),new Source("ARTICLE",null,a.getArticleId(),null,a.getTitle(),a.getArticleCode(),a.getAnswer(),0.80,a.getEffectiveFrom()));}}catch(RuntimeException ignored){}
        return result.values().stream().sorted(Comparator.comparingDouble(Source::score).reversed()).limit(5).toList();}
    private boolean eligible(KnowledgeChunk c,boolean internal){KnowledgeDocument d=c.getVersion().getDocument();Instant now=Instant.now(clock);return "READY".equals(d.getStatus())&&d.getCurrentVersion()!=null&&d.getCurrentVersion().getVersionId().equals(c.getVersion().getVersionId())&&(internal||"PUBLIC".equals(d.getVisibility()))&&(d.getEffectiveFrom()==null||!d.getEffectiveFrom().isAfter(now))&&(d.getEffectiveUntil()==null||d.getEffectiveUntil().isAfter(now));}
    private boolean eligible(HospitalInformationArticle a,boolean internal){HospitalInformation h=a.getHospitalInformation();Instant now=Instant.now(clock);return a.getStatus()==HospitalInformationStatus.PUBLISHED&&h.getStatus()==HospitalInformationStatus.PUBLISHED&&(internal||a.isPublicVisible()&&h.isPublicVisible())&&(a.getEffectiveFrom()==null||!a.getEffectiveFrom().isAfter(now))&&(a.getEffectiveUntil()==null||a.getEffectiveUntil().isAfter(now))&&(h.getEffectiveFrom()==null||!h.getEffectiveFrom().isAfter(now))&&(h.getEffectiveUntil()==null||h.getEffectiveUntil().isAfter(now));}
    private String locator(KnowledgeChunk c){if(c.getPageFrom()!=null)return "Trang "+c.getPageFrom();if(c.getHeading()!=null)return c.getHeading();return "Đoạn "+(c.getChunkIndex()+1);}
}
