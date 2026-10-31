package com.mediassistrover.backend.assistant;
import java.time.Instant;
import java.util.*;
public final class AssistantDtos {
    private AssistantDtos(){}
    public record CreateSessionRequest(UUID robotId,String language){}
    public record SessionResponse(UUID sessionId,UUID robotId,String channel,String scope,String language,Instant expiresAt){}
    public record TextTurnRequest(String text){}
    public record CitationResponse(int ordinal,String sourceType,UUID documentId,UUID articleId,UUID chunkId,String title,String locator,double score){}
    public record ActionPreview(String intent,String destination,UUID endpointId,boolean confirmationRequired,Instant expiresAt){}
    public record TurnResponse(UUID turnId,UUID sessionId,int sequence,String status,String transcript,String language,String intent,String answer,String speechText,String groundingMode,List<String> warnings,String model,Long latencyMs,List<CitationResponse> citations,ActionPreview actionPreview,String confirmationNonce,UUID serviceRequestId,UUID jobId,String audioUrl,String audioSha256,String errorCode,String errorMessage,Instant createdAt,Instant completedAt){}
    public record ConfirmRequest(String nonce){}
}
