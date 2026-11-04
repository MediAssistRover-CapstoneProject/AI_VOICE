package com.mediassistrover.backend.knowledge;

import com.mediassistrover.backend.assistant.OpenAiGateway;
import com.mediassistrover.backend.config.AssistantProperties;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.knowledge.KnowledgeDtos.EditRequest;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeAdminServiceTest {
    @TempDir Path storage;
    final KnowledgeDocumentRepository documents=mock(KnowledgeDocumentRepository.class);
    final KnowledgeDocumentVersionRepository versions=mock(KnowledgeDocumentVersionRepository.class);
    final KnowledgeChunkRepository chunks=mock(KnowledgeChunkRepository.class);
    final KnowledgeIngestionJobRepository jobs=mock(KnowledgeIngestionJobRepository.class);
    final AccountRepository accounts=mock(AccountRepository.class);
    final DocumentExtractor extractor=mock(DocumentExtractor.class);
    final OpenAiGateway openAi=mock(OpenAiGateway.class);
    final QdrantKnowledgeStore qdrant=mock(QdrantKnowledgeStore.class);
    final Instant now=Instant.parse("2026-11-02T00:00:00Z");
    final Account admin=Account.builder().accountId(UUID.randomUUID()).role(Role.ADMIN).status(AccountStatus.ACTIVE).build();
    final AccountPrincipal actor=AccountPrincipal.fromAccount(admin);
    KnowledgeAdminService service;

    @BeforeEach void setup(){
        AssistantProperties properties=new AssistantProperties();properties.setEnabled(true);
        properties.setAutomaticDeduplicationEnabled(false);properties.setKnowledgeStorageDir(storage);
        service=new KnowledgeAdminService(documents,versions,chunks,jobs,accounts,properties,extractor,openAi,qdrant,Clock.fixed(now,ZoneOffset.UTC),mock(AuditService.class));
        when(accounts.findById(admin.getAccountId())).thenReturn(Optional.of(admin));
        when(documents.saveAndFlush(any())).thenAnswer(call->{KnowledgeDocument d=call.getArgument(0);if(d.getDocumentId()==null)d.setDocumentId(UUID.randomUUID());return d;});
        when(versions.saveAndFlush(any())).thenAnswer(call->{KnowledgeDocumentVersion v=call.getArgument(0);v.setVersionId(UUID.randomUUID());return v;});
    }

    @Test void textCreationStoresUtf8AndQueuesIngestionWithoutBlockingOnAi() throws Exception {
        service.saveText(null,new EditRequest("Giờ khám","HOSPITAL_INFO","vi-VN","PUBLIC","Khám bệnh từ 07:00.",null),actor);
        var saved=ArgumentCaptor.forClass(KnowledgeDocumentVersion.class);verify(versions).saveAndFlush(saved.capture());
        KnowledgeDocumentVersion v=saved.getValue();
        assertThat(Files.readString(Path.of(v.getStoragePath()))).isEqualTo("Khám bệnh từ 07:00.");
        assertThat(v.getMediaType()).isEqualTo("text/plain");assertThat(v.getVersionNo()).isEqualTo(1);
        verify(jobs).save(argThat(j->j.getVersion()==v&&"PENDING".equals(j.getStatus())));
        verifyNoInteractions(openAi,qdrant,extractor);
    }

    @Test void staleEditorCannotOverwriteANewerRevision(){
        KnowledgeDocument d=documentWithVersion("READY");
        assertThatThrownBy(()->service.saveText(d.getDocumentId(),new EditRequest("Mới","HOSPITAL_INFO","vi-VN","PUBLIC","Nội dung",UUID.randomUUID()),actor))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("đã thay đổi");
        verify(versions,never()).saveAndFlush(any());verifyNoInteractions(jobs,qdrant);
    }

    @Test void editingCreatesVersionAndKeepsPriorCitationsIntact(){
        KnowledgeDocument d=documentWithVersion("READY");KnowledgeDocumentVersion prior=d.getCurrentVersion();
        service.saveText(d.getDocumentId(),new EditRequest("Giờ khám mới","HOSPITAL_INFO","vi-VN","INTERNAL","Mở cửa từ 08:00.",prior.getVersionId()),actor);
        verify(versions).saveAndFlush(argThat(v->v.getVersionNo()==2&&v.getDocument()==d));
        assertThat(d.getCurrentVersion()).isSameAs(prior);assertThat(d.getStatus()).isEqualTo("UPLOADED");assertThat(d.getVisibility()).isEqualTo("INTERNAL");
        verify(chunks,never()).deleteByVersionVersionId(any());verifyNoInteractions(qdrant);
    }

    @Test void reindexCreatesNewVersionInsteadOfDeletingReferencedChunks(){
        KnowledgeDocument d=documentWithVersion("READY");KnowledgeDocumentVersion prior=d.getCurrentVersion();
        service.reindex(d.getDocumentId());
        verify(versions).saveAndFlush(argThat(v->v.getVersionNo()==2&&v.getStoragePath().equals(prior.getStoragePath())&&v.getSha256().equals(prior.getSha256())));
        verify(chunks,never()).deleteByVersionVersionId(any());verifyNoInteractions(qdrant);
        assertThat(d.getCurrentVersion()).isSameAs(prior);assertThat(prior.getStatus()).isEqualTo("READY");
    }

    @Test void archiveImmediatelyDisqualifiesDocumentEvenWhenVectorCleanupFails(){
        KnowledgeDocument d=documentWithVersion("READY");
        doThrow(new IllegalStateException("offline")).when(qdrant).deleteVersion(any());
        service.archive(d.getDocumentId());
        assertThat(d.getStatus()).isEqualTo("ARCHIVED");assertThat(d.getCurrentVersion().getStatus()).isEqualTo("ARCHIVED");
        verify(jobs).cancelPending(d.getDocumentId());verify(chunks,never()).deleteByVersionVersionId(any());
    }

    @Test void anotherWorkerClaimingTheJobPreventsRepeatedIngestion() throws Exception {
        KnowledgeDocument d=documentWithVersion("UPLOADED");
        KnowledgeIngestionJob job=KnowledgeIngestionJob.builder().ingestionJobId(UUID.randomUUID()).version(d.getCurrentVersion()).status("PENDING").build();
        when(jobs.findFirstByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"),any())).thenReturn(Optional.of(job));
        when(jobs.claimPending(job.getIngestionJobId())).thenReturn(0);
        service.processNext();
        verifyNoInteractions(extractor,openAi,qdrant);verify(chunks,never()).saveAll(any());
    }

    @Test void successfulIngestionPublishesNewVersionThenRemovesOldVectors() throws Exception {
        KnowledgeDocument d=documentWithVersion("UPLOADED");KnowledgeDocumentVersion prior=d.getCurrentVersion();
        KnowledgeDocumentVersion incoming=KnowledgeDocumentVersion.builder().versionId(UUID.randomUUID()).versionNo(2).document(d).storagePath(storage.resolve("new.txt").toString()).mediaType("text/plain").status("UPLOADED").build();
        when(versions.findByDocumentDocumentIdOrderByVersionNoDesc(d.getDocumentId())).thenReturn(List.of(incoming,prior));
        KnowledgeIngestionJob job=KnowledgeIngestionJob.builder().ingestionJobId(UUID.randomUUID()).version(incoming).status("PENDING").build();
        when(jobs.findFirstByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"),any())).thenReturn(Optional.of(job));
        when(jobs.claimPending(job.getIngestionJobId())).thenReturn(1);
        when(extractor.extract(any(),eq("text/plain"))).thenReturn(new DocumentExtractor.Extracted("Nhà thuốc ở tầng 1.",1,"TEXT"));
        when(openAi.embeddings(any())).thenReturn(List.of(new float[]{0.1f}));
        service.processNext();
        assertThat(d.getCurrentVersion()).isSameAs(incoming);assertThat(d.getStatus()).isEqualTo("READY");assertThat(job.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(prior.getStatus()).isEqualTo("ARCHIVED");verify(qdrant).deleteVersion(prior.getVersionId());
        var order=inOrder(qdrant);order.verify(qdrant).upsert(any(),any());order.verify(qdrant).deleteVersion(prior.getVersionId());
    }

    private KnowledgeDocument documentWithVersion(String status){
        KnowledgeDocument d=KnowledgeDocument.builder().documentId(UUID.randomUUID()).title("Giờ khám").category("HOSPITAL_INFO").languageCode("vi-VN").visibility("PUBLIC").status(status).createdBy(admin).createdAt(now).build();
        KnowledgeDocumentVersion v=KnowledgeDocumentVersion.builder().versionId(UUID.randomUUID()).versionNo(1).document(d).status("READY").storagePath(storage.resolve("old.txt").toString()).mediaType("text/plain").originalFilename("old.txt").sha256("a".repeat(64)).sizeBytes(24).build();
        d.setCurrentVersion(v);when(documents.lockById(d.getDocumentId())).thenReturn(Optional.of(d));
        when(versions.findByDocumentDocumentIdOrderByVersionNoDesc(d.getDocumentId())).thenReturn(List.of(v));return d;
    }
}
