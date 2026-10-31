package com.mediassistrover.backend.assistant;
import com.mediassistrover.backend.config.AssistantProperties;
import com.mediassistrover.backend.repository.VoiceAssistantTurnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
@Component @RequiredArgsConstructor
public class AssistantRetentionScheduler {
    private final VoiceAssistantTurnRepository turns;private final AssistantProperties properties;private final Clock clock;
    @Scheduled(cron="${app.assistant.retention-cron:0 10 3 * * *}") @Transactional
    public void purge(){if(!properties.isEnabled())return;Instant cutoff=Instant.now(clock).minus(properties.getRetentionDays(),ChronoUnit.DAYS);turns.findByCreatedAtBefore(cutoff).stream().map(t->t.getAudioPath()).filter(java.util.Objects::nonNull).forEach(path->{try{Files.deleteIfExists(Path.of(path));}catch(Exception ignored){}});turns.deleteByCreatedAtBefore(cutoff);}
}
