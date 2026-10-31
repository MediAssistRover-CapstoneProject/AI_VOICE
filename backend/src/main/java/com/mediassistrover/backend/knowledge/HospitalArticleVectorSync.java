package com.mediassistrover.backend.knowledge;

import com.mediassistrover.backend.assistant.OpenAiGateway;
import com.mediassistrover.backend.config.AssistantProperties;
import com.mediassistrover.backend.entity.HospitalInformationArticle;
import com.mediassistrover.backend.enums.HospitalInformationStatus;
import com.mediassistrover.backend.repository.HospitalInformationArticleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service @RequiredArgsConstructor
public class HospitalArticleVectorSync {
    private final HospitalInformationArticleRepository articles;
    private final OpenAiGateway openAi;
    private final QdrantKnowledgeStore qdrant;
    private final AssistantProperties properties;
    private final Map<UUID,Instant> synced = new ConcurrentHashMap<>();

    @Scheduled(initialDelayString="${app.assistant.article-sync-initial-ms:30000}",fixedDelayString="${app.assistant.article-sync-ms:300000}")
    @Transactional(readOnly=true)
    public void sync(){
        if(!properties.isEnabled()||properties.getOpenaiApiKey()==null||properties.getOpenaiApiKey().isBlank())return;
        List<HospitalInformationArticle> changed=articles.findAll().stream()
                .filter(a->a.getStatus()==HospitalInformationStatus.PUBLISHED)
                .filter(a->!Objects.equals(synced.get(a.getArticleId()),a.getUpdatedAt())).toList();
        for(int start=0;start<changed.size();start+=32){
            List<HospitalInformationArticle> batch=changed.subList(start,Math.min(start+32,changed.size()));
            List<String> text=batch.stream().map(a->a.getTitle()+"\n"+a.getCanonicalQuestion()+"\n"+a.getAnswer()).toList();
            qdrant.upsertArticles(batch,openAi.embeddings(text));
            batch.forEach(a->synced.put(a.getArticleId(),a.getUpdatedAt()));
        }
    }
}
