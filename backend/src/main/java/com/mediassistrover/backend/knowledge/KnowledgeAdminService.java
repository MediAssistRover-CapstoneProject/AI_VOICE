package com.mediassistrover.backend.knowledge;

import com.mediassistrover.backend.assistant.OpenAiGateway;
import com.mediassistrover.backend.config.AssistantProperties;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.knowledge.KnowledgeDtos.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.security.AccountPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class KnowledgeAdminService {
    private static final Set<String> TYPES=Set.of("application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","image/jpeg","image/png");
    private final KnowledgeDocumentRepository documents;private final KnowledgeDocumentVersionRepository versions;private final KnowledgeChunkRepository chunks;private final KnowledgeIngestionJobRepository jobs;private final AccountRepository accounts;
    private final AssistantProperties properties;private final DocumentExtractor extractor;private final OpenAiGateway openAi;private final QdrantKnowledgeStore qdrant;private final Clock clock;

    @Transactional
    public DocumentView upload(MultipartFile file,String title,String category,String language,String visibility,Instant effectiveFrom,Instant effectiveUntil,UUID replacesId,AccountPrincipal actor){
        if(!properties.isEnabled())throw new BusinessRuleException("Knowledge assistant is disabled");if(file==null||file.isEmpty())throw new BusinessRuleException("A document file is required");if(file.getSize()>25L*1024*1024)throw new BusinessRuleException("File exceeds 25 MB");
        String type=mediaType(file);if(!TYPES.contains(type))throw new BusinessRuleException("Only PDF, DOCX, JPG and PNG are supported");
        String cleanTitle=required(title,"title",255),cleanCategory=required(category,"category",80);String lang=normalizeLanguage(language);String scope=normalizeVisibility(visibility);if(effectiveFrom!=null&&effectiveUntil!=null&&!effectiveFrom.isBefore(effectiveUntil))throw new BusinessRuleException("effectiveFrom must be before effectiveUntil");
        Account account=accounts.findById(actor.accountId()).orElseThrow(()->new ResourceNotFoundException("Account not found"));KnowledgeDocument replaced=replacesId==null?null:document(replacesId);if(replaced!=null&&"ARCHIVED".equals(replaced.getStatus()))throw new BusinessRuleException("Cannot replace an archived document");
        Instant now=Instant.now(clock);KnowledgeDocument document=documents.saveAndFlush(KnowledgeDocument.builder().title(cleanTitle).category(cleanCategory).languageCode(lang).visibility(scope).status("UPLOADED").replacesDocument(replaced).effectiveFrom(effectiveFrom).effectiveUntil(effectiveUntil).createdBy(account).createdAt(now).updatedAt(now).build());
        String original=safeName(file.getOriginalFilename());KnowledgeDocumentVersion version=versions.save(KnowledgeDocumentVersion.builder().document(document).versionNo(1).originalFilename(original).mediaType(type).storagePath("").sha256("").sizeBytes(file.getSize()).status("UPLOADED").createdAt(now).build());
        UUID versionId=version.getVersionId();Path directory=properties.getKnowledgeStorageDir().resolve(document.getDocumentId().toString());Path path=directory.resolve(versionId+extension(original));
        try{Files.createDirectories(directory);file.transferTo(path);}catch(Exception e){throw new IllegalStateException("Unable to persist uploaded document",e);}
        version.setStoragePath(path.toAbsolutePath().normalize().toString());version.setSha256(sha256(path));versions.flush();
        jobs.save(KnowledgeIngestionJob.builder().version(version).status("PENDING").attemptCount(0).nextAttemptAt(now).createdAt(now).updatedAt(now).build());return view(document,true);
    }
    @Transactional(readOnly=true) public List<DocumentView> list(){return documents.findAll().stream().sorted(Comparator.comparing(KnowledgeDocument::getCreatedAt).reversed()).map(d->view(d,false)).toList();}
    @Transactional(readOnly=true) public DocumentView get(UUID id){return view(document(id),true);}
    @Transactional public DocumentView reindex(UUID id){KnowledgeDocument d=document(id);KnowledgeDocumentVersion v=d.getCurrentVersion();if(v==null)throw new BusinessRuleException("Document has no ready version");try{qdrant.deleteVersion(v.getVersionId());}catch(RuntimeException ignored){}chunks.deleteByVersionVersionId(v.getVersionId());Instant now=Instant.now(clock);v.setStatus("UPLOADED");v.setErrorCode(null);v.setErrorMessage(null);v.setReadyAt(null);d.setStatus("PROCESSING");jobs.save(KnowledgeIngestionJob.builder().version(v).status("PENDING").attemptCount(0).nextAttemptAt(now).createdAt(now).updatedAt(now).build());return view(d,true);}
    @Transactional public void archive(UUID id){KnowledgeDocument d=document(id);d.setStatus("ARCHIVED");if(d.getCurrentVersion()!=null){d.getCurrentVersion().setStatus("ARCHIVED");try{qdrant.deleteVersion(d.getCurrentVersion().getVersionId());}catch(RuntimeException ignored){}}}

    @Scheduled(fixedDelayString="${app.assistant.ingestion-poll-ms:5000}") @Transactional
    public void processNext(){if(!properties.isEnabled())return;Optional<KnowledgeIngestionJob> next=jobs.findFirstByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("PENDING",Instant.now(clock));if(next.isEmpty())return;KnowledgeIngestionJob job=next.get();KnowledgeDocumentVersion version=job.getVersion();KnowledgeDocument document=version.getDocument();Instant now=Instant.now(clock);job.setStatus("RUNNING");job.setStartedAt(now);job.setAttemptCount(job.getAttemptCount()+1);job.setUpdatedAt(now);document.setStatus("PROCESSING");
        try{version.setStatus("EXTRACTING");DocumentExtractor.Extracted extracted=extractor.extract(Path.of(version.getStoragePath()),version.getMediaType());version.setStatus(extracted.method().contains("OCR")||"OPENAI_VISION".equals(extracted.method())?"OCR":"CHUNKING");version.setExtractedText(extracted.text());version.setPageCount(extracted.pages());version.setExtractionMethod(extracted.method());
            version.setStatus("CHUNKING");List<KnowledgeChunk> values=chunk(version,extracted.text(),now);chunks.saveAll(values);chunks.flush();version.setStatus("EMBEDDING");for(int start=0;start<values.size();start+=32){List<KnowledgeChunk> batch=values.subList(start,Math.min(start+32,values.size()));List<float[]> vectors=openAi.embeddings(batch.stream().map(KnowledgeChunk::getContent).toList());qdrant.upsert(batch,vectors);}
            version.setStatus("READY");version.setReadyAt(Instant.now(clock));document.setCurrentVersion(version);document.setStatus("READY");if(document.getReplacesDocument()!=null){KnowledgeDocument old=document.getReplacesDocument();if(old.getCurrentVersion()!=null)try{qdrant.deleteVersion(old.getCurrentVersion().getVersionId());}catch(RuntimeException ignored){}old.setStatus("ARCHIVED");if(old.getCurrentVersion()!=null)old.getCurrentVersion().setStatus("ARCHIVED");}
            job.setStatus("SUCCEEDED");job.setEndedAt(Instant.now(clock));job.setUpdatedAt(job.getEndedAt());
        }catch(Exception e){version.setStatus("FAILED");version.setErrorCode(code(e));version.setErrorMessage(limit(rootMessage(e),1000));document.setStatus("FAILED");job.setStatus("FAILED");job.setErrorCode(version.getErrorCode());job.setErrorMessage(version.getErrorMessage());job.setEndedAt(Instant.now(clock));job.setUpdatedAt(job.getEndedAt());}
    }
    private List<KnowledgeChunk> chunk(KnowledgeDocumentVersion version,String text,Instant now){List<KnowledgeChunk> out=new ArrayList<>();String normalized=text.replace("\r","");int start=0,index=0;while(start<normalized.length()){int end=Math.min(normalized.length(),start+2800);if(end<normalized.length()){int boundary=normalized.lastIndexOf('\n',end);if(boundary>start+1400)end=boundary;}String value=normalized.substring(start,end).trim();if(!value.isBlank()){Integer page=pageAt(normalized,start);out.add(KnowledgeChunk.builder().version(version).chunkIndex(index++).heading(heading(value)).pageFrom(page).pageTo(page).content(value).tokenEstimate(Math.max(1,value.length()/4)).qdrantPointId(UUID.randomUUID().toString()).createdAt(now).build());}if(end==normalized.length())break;start=Math.max(start+1,end-400);}if(out.isEmpty())throw new BusinessRuleException("Document did not produce any chunks");return out;}
    private Integer pageAt(String text,int offset){int p=text.lastIndexOf("[PAGE ",offset);if(p<0)return null;int e=text.indexOf(']',p);try{return Integer.parseInt(text.substring(p+6,e));}catch(Exception ex){return null;}}
    private String heading(String value){int h=value.lastIndexOf("[HEADING] ",Math.min(value.length()-1,300));if(h<0)return null;int e=value.indexOf('\n',h);return limit(value.substring(h+10,e<0?value.length():e).trim(),500);}
    private DocumentView view(KnowledgeDocument d,boolean withChunks){KnowledgeDocumentVersion v=d.getCurrentVersion();if(v==null)v=versions.findByDocumentDocumentIdOrderByVersionNoDesc(d.getDocumentId()).stream().findFirst().orElse(null);List<ChunkView> cv=withChunks&&v!=null?chunks.findByVersionVersionIdOrderByChunkIndexAsc(v.getVersionId()).stream().map(c->new ChunkView(c.getChunkId(),c.getChunkIndex(),c.getHeading(),c.getPageFrom(),c.getPageTo(),c.getContent(),c.getTokenEstimate())).toList():List.of();String preview=v==null||v.getExtractedText()==null?null:limit(v.getExtractedText(),3000);return new DocumentView(d.getDocumentId(),d.getTitle(),d.getCategory(),d.getLanguageCode(),d.getVisibility(),d.getStatus(),d.getReplacesDocument()==null?null:d.getReplacesDocument().getDocumentId(),d.getCurrentVersion()==null?null:d.getCurrentVersion().getVersionId(),v==null?null:v.getOriginalFilename(),v==null?null:v.getMediaType(),v==null?null:v.getPageCount(),v==null?null:v.getExtractionMethod(),v==null?null:v.getErrorCode(),v==null?null:v.getErrorMessage(),preview,d.getEffectiveFrom(),d.getEffectiveUntil(),d.getCreatedAt(),d.getUpdatedAt(),cv);}
    private KnowledgeDocument document(UUID id){return documents.findById(id).orElseThrow(()->new ResourceNotFoundException("KnowledgeDocument not found: "+id));}
    private String mediaType(MultipartFile file){String type=file.getContentType();String name=Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);if(type==null||type.equals("application/octet-stream")){if(name.endsWith(".pdf"))type="application/pdf";else if(name.endsWith(".docx"))type="application/vnd.openxmlformats-officedocument.wordprocessingml.document";else if(name.endsWith(".jpg")||name.endsWith(".jpeg"))type="image/jpeg";else if(name.endsWith(".png"))type="image/png";}return type==null?"":type.toLowerCase(Locale.ROOT);}
    private String normalizeLanguage(String v){String x=required(v,"language",16).replace('_','-');if(!Set.of("vi","vi-VN","en","en-US").contains(x))throw new BusinessRuleException("language must be Vietnamese or English");return x;}
    private String normalizeVisibility(String v){String x=required(v,"visibility",16).toUpperCase(Locale.ROOT);if(!Set.of("PUBLIC","INTERNAL").contains(x))throw new BusinessRuleException("visibility must be PUBLIC or INTERNAL");return x;}
    private String required(String v,String name,int max){if(v==null||v.isBlank()||v.trim().length()>max)throw new BusinessRuleException(name+" is required and must be at most "+max+" characters");return v.trim();}
    private String safeName(String v){String x=v==null?"document":Path.of(v).getFileName().toString();return limit(x.replaceAll("[^\\p{L}\\p{N}._ -]","_"),255);}private String extension(String v){int i=v.lastIndexOf('.');return i<0?"":v.substring(i).toLowerCase(Locale.ROOT);}
    private String sha256(Path path){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String code(Exception e){String m=rootMessage(e);if(m.matches("[A-Z0-9_]+"))return limit(m,80);return "INGESTION_FAILED";}private String rootMessage(Throwable e){Throwable x=e;while(x.getCause()!=null)x=x.getCause();return Optional.ofNullable(x.getMessage()).orElse(x.getClass().getSimpleName());}private static String limit(String v,int n){return v==null?null:v.substring(0,Math.min(n,v.length()));}
}
