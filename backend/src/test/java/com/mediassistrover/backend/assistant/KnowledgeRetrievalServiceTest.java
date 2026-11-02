package com.mediassistrover.backend.assistant;

import com.mediassistrover.backend.config.AssistantProperties;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.knowledge.QdrantKnowledgeStore;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.HospitalInformationQueryService;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KnowledgeRetrievalServiceTest {
    @Test void qdrantFailureStillReturnsKeywordTableChunk(){
        OpenAiGateway openAi=mock(OpenAiGateway.class);QdrantKnowledgeStore qdrant=mock(QdrantKnowledgeStore.class);KnowledgeChunkRepository chunks=mock(KnowledgeChunkRepository.class);HospitalInformationQueryService hospital=mock(HospitalInformationQueryService.class);HospitalInformationArticleRepository articles=mock(HospitalInformationArticleRepository.class);AssistantProperties properties=new AssistantProperties();properties.setHybridRetrievalEnabled(true);
        KnowledgeDocument document=KnowledgeDocument.builder().documentId(UUID.randomUUID()).title("Thông tin bệnh viện").languageCode("vi-VN").visibility("PUBLIC").status("READY").build();KnowledgeDocumentVersion version=KnowledgeDocumentVersion.builder().versionId(UUID.randomUUID()).document(document).status("READY").build();document.setCurrentVersion(version);KnowledgeChunk chunk=KnowledgeChunk.builder().chunkId(UUID.randomUUID()).version(version).chunkIndex(0).chunkType("TABLE").contentHash("abc").heading("Nhà thuốc ngoại trú").locator("Bảng giờ phục vụ hàng Thứ Bảy").content("Thứ Bảy | 07:00 | 13:00").build();
        when(openAi.embeddings(any())).thenReturn(List.of(new float[]{0.1f}));when(qdrant.search(any(),eq(false))).thenThrow(new IllegalStateException("offline"));when(chunks.keywordSearch(anyString(),eq(false))).thenReturn(List.of(chunk));
        var service=new KnowledgeRetrievalService(openAi,qdrant,chunks,hospital,articles,properties,Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"),ZoneOffset.UTC));
        var result=service.retrieve("Nhà thuốc mở đến mấy giờ thứ Bảy?","vi-VN",false);
        assertThat(result).hasSize(1);assertThat(result.getFirst().sourceId()).isEqualTo("S1");assertThat(result.getFirst().locator()).contains("Thứ Bảy");
    }
}
