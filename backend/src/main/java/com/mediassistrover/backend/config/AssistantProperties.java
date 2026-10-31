package com.mediassistrover.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@ConfigurationProperties(prefix = "app.assistant")
public class AssistantProperties {
    private boolean enabled;
    private boolean publicEnabled;
    private boolean voiceActionsEnabled;
    private boolean piBridgeEnabled;
    private String publicRobotIds = "";
    private int sessionTtlMinutes = 30;
    private int retentionDays = 90;
    private String openaiApiKey = "";
    private String responseModel = "gpt-6.1-sol";
    private String ocrModel = "gpt-6.1-terra";
    private String embeddingModel = "text-embedding-3-small";
    private String transcribeModel = "gpt-transcribe";
    private String ttsModel = "gpt-realtime-2.1-mini";
    private String ttsVoice = "coral";
    private String qdrantUrl = "http://localhost:6333";
    private String qdrantCollection = "mediassist_knowledge";
    private Path knowledgeStorageDir = Path.of("./data/knowledge");
    private Path speechStorageDir = Path.of("./data/speech");

    public Set<UUID> publicRobotIdSet() {
        if (publicRobotIds == null || publicRobotIds.isBlank()) return Set.of();
        return Arrays.stream(publicRobotIds.split(",")).map(String::trim).filter(s -> !s.isBlank())
                .map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
    }
    public Duration sessionTtl() { return Duration.ofMinutes(sessionTtlMinutes); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isPublicEnabled() { return publicEnabled; }
    public void setPublicEnabled(boolean publicEnabled) { this.publicEnabled = publicEnabled; }
    public boolean isVoiceActionsEnabled() { return voiceActionsEnabled; }
    public void setVoiceActionsEnabled(boolean voiceActionsEnabled) { this.voiceActionsEnabled = voiceActionsEnabled; }
    public boolean isPiBridgeEnabled() { return piBridgeEnabled; }
    public void setPiBridgeEnabled(boolean piBridgeEnabled) { this.piBridgeEnabled = piBridgeEnabled; }
    public String getPublicRobotIds() { return publicRobotIds; }
    public void setPublicRobotIds(String publicRobotIds) { this.publicRobotIds = publicRobotIds; }
    public int getSessionTtlMinutes() { return sessionTtlMinutes; }
    public void setSessionTtlMinutes(int sessionTtlMinutes) { this.sessionTtlMinutes = sessionTtlMinutes; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    public String getOpenaiApiKey() { return openaiApiKey; }
    public void setOpenaiApiKey(String openaiApiKey) { this.openaiApiKey = openaiApiKey; }
    public String getResponseModel() { return responseModel; }
    public void setResponseModel(String responseModel) { this.responseModel = responseModel; }
    public String getOcrModel() { return ocrModel; }
    public void setOcrModel(String ocrModel) { this.ocrModel = ocrModel; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public String getTranscribeModel() { return transcribeModel; }
    public void setTranscribeModel(String transcribeModel) { this.transcribeModel = transcribeModel; }
    public String getTtsModel() { return ttsModel; }
    public void setTtsModel(String ttsModel) { this.ttsModel = ttsModel; }
    public String getTtsVoice() { return ttsVoice; }
    public void setTtsVoice(String ttsVoice) { this.ttsVoice = ttsVoice; }
    public String getQdrantUrl() { return qdrantUrl; }
    public void setQdrantUrl(String qdrantUrl) { this.qdrantUrl = qdrantUrl; }
    public String getQdrantCollection() { return qdrantCollection; }
    public void setQdrantCollection(String qdrantCollection) { this.qdrantCollection = qdrantCollection; }
    public Path getKnowledgeStorageDir() { return knowledgeStorageDir; }
    public void setKnowledgeStorageDir(Path knowledgeStorageDir) { this.knowledgeStorageDir = knowledgeStorageDir; }
    public Path getSpeechStorageDir() { return speechStorageDir; }
    public void setSpeechStorageDir(Path speechStorageDir) { this.speechStorageDir = speechStorageDir; }
}
