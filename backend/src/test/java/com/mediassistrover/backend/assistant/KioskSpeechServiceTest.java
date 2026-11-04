package com.mediassistrover.backend.assistant;

import com.mediassistrover.backend.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KioskSpeechServiceTest {
    private final AssistantService assistant = mock(AssistantService.class);
    private final VoiceSettingsService settings = mock(VoiceSettingsService.class);
    private final OpenAiGateway gateway = mock(OpenAiGateway.class);
    private final KioskSpeechService service = new KioskSpeechService(assistant, settings, gateway);
    private final UUID sessionId = UUID.randomUUID();

    private VoiceSettingsService.Settings voice(long revision) {
        return new VoiceSettingsService.Settings("FEMALE", "coral", "CALM", 1.0, "", "CLOUD", revision);
    }

    @BeforeEach void setup() {
        when(assistant.kioskSpeechText(sessionId, null)).thenReturn(KioskSpeechService.GREETING);
        when(settings.current()).thenReturn(voice(1));
    }

    @Test void cacheReusesAudioButEveryRequestRechecksSession() {
        when(gateway.synthesizeWav(anyString(), eq("vi-VN"), any())).thenReturn(new byte[]{1, 2});
        service.synthesize(sessionId, null);
        assertThat(service.synthesize(sessionId, null)).containsExactly(1, 2);
        verify(gateway, times(1)).synthesizeWav(anyString(), anyString(), any());
        verify(assistant, times(2)).kioskSpeechText(sessionId, null);
        when(assistant.kioskSpeechText(sessionId, null)).thenThrow(new BusinessRuleException("Session expired"));
        assertThatThrownBy(() -> service.synthesize(sessionId, null)).isInstanceOf(BusinessRuleException.class);
    }

    @Test void changedRevisionDoesNotReuseOldVoice() {
        when(gateway.synthesizeWav(anyString(), eq("vi-VN"), eq(voice(1)))).thenReturn(new byte[]{1});
        when(gateway.synthesizeWav(anyString(), eq("vi-VN"), eq(voice(2)))).thenReturn(new byte[]{2});
        assertThat(service.synthesize(sessionId, null)).containsExactly(1);
        when(settings.current()).thenReturn(voice(2));
        assertThat(service.synthesize(sessionId, null)).containsExactly(2);
    }

    @Test void browserModeCannotInvokeCloudSynthesis() {
        when(settings.current()).thenReturn(new VoiceSettingsService.Settings("FEMALE", "coral", "CALM", 1, "", "BROWSER", 1));
        assertThatThrownBy(() -> service.synthesize(sessionId, null)).isInstanceOf(BusinessRuleException.class);
        verifyNoInteractions(gateway);
    }

    @Test void entryLimitEvictsLeastRecentlyUsedAudio() {
        when(gateway.synthesizeWav(anyString(), anyString(), any())).thenReturn(new byte[]{1});
        for (int i = 0; i < 65; i++) {
            when(assistant.kioskSpeechText(sessionId, null)).thenReturn("answer " + i);
            service.synthesize(sessionId, null);
        }
        when(assistant.kioskSpeechText(sessionId, null)).thenReturn("answer 0");
        service.synthesize(sessionId, null);
        verify(gateway, times(2)).synthesizeWav(eq("answer 0"), anyString(), any());
    }

    @Test void byteLimitEvictsAudioWithoutRequiringEntryLimit() {
        when(gateway.synthesizeWav(anyString(), anyString(), any())).thenAnswer(call -> new byte[5 * 1024 * 1024]);
        service.synthesize(sessionId, null);
        when(assistant.kioskSpeechText(sessionId, null)).thenReturn("another answer");
        service.synthesize(sessionId, null);
        when(assistant.kioskSpeechText(sessionId, null)).thenReturn(KioskSpeechService.GREETING);
        service.synthesize(sessionId, null);
        verify(gateway, times(2)).synthesizeWav(eq(KioskSpeechService.GREETING), anyString(), any());
    }
}
