package com.mediassistrover.backend.knowledge;
import java.time.Instant;
import java.util.*;
public final class KnowledgeDtos {
    private KnowledgeDtos(){}
    public record ChunkView(UUID id,int index,String heading,Integer pageFrom,Integer pageTo,String content,int tokenEstimate){}
    public record DocumentView(UUID id,String title,String category,String language,String visibility,String status,UUID replacesDocumentId,UUID currentVersionId,String filename,String mediaType,Integer pageCount,String extractionMethod,String errorCode,String errorMessage,String preview,Instant effectiveFrom,Instant effectiveUntil,Instant createdAt,Instant updatedAt,List<ChunkView> chunks){}
}
