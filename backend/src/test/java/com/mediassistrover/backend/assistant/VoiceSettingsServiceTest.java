package com.mediassistrover.backend.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.sql.ResultSet;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoiceSettingsServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper json = new ObjectMapper();
    private final VoiceSettingsService service = new VoiceSettingsService(jdbc, json);

    @Test void oldStoredRowsKeepBrowserPlaybackAndUseDatabaseRevision() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn("{\"profile\":\"FEMALE\",\"cloudVoice\":\"coral\",\"style\":\"CALM\",\"rate\":1.0,\"browserVoiceUri\":\"\"}");
        when(row.getLong(2)).thenReturn(7L);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class))).thenAnswer(call ->
                ((RowMapper<?>) call.getArgument(1)).mapRow(row, 0));
        var current = service.current();
        assertThat(current.kioskEngine()).isEqualTo("BROWSER");
        assertThat(current.revision()).isEqualTo(7);
    }

    @Test void rejectsInvalidVoiceRateAndEngineWithoutWriting() {
        assertThatThrownBy(() -> service.save(settings("invented",1,"BROWSER",0))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.save(settings("coral",Double.NaN,"BROWSER",0))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.save(settings("coral",1.51,"BROWSER",0))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.save(settings("coral",1,"unknown",0))).isInstanceOf(BusinessRuleException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void staleRevisionCannotOverwriteAnotherAdministrator() {
        when(jdbc.update(anyString(),anyString(),eq(3L))).thenReturn(0);
        assertThatThrownBy(() -> service.save(settings("cedar",1.2,"CLOUD",3)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("nơi khác");
        verify(jdbc).update(contains("AND revision=?"),anyString(),eq(3L));
    }

    @Test void savesPlaybackSelectionWithOptimisticRevision() throws Exception {
        var setting = settings("cedar",1.2,"CLOUD",3);
        when(jdbc.update(anyString(),anyString(),eq(3L))).thenReturn(1);
        var stored = settings("cedar",1.2,"CLOUD",4);
        when(jdbc.queryForObject(anyString(),any(RowMapper.class))).thenReturn(stored);
        assertThat(service.save(setting)).isEqualTo(stored);
        verify(jdbc).update(contains("revision=revision+1"),eq(json.writeValueAsString(setting)),eq(3L));
    }

    private VoiceSettingsService.Settings settings(String voice,double rate,String engine,long revision) {
        return new VoiceSettingsService.Settings("CUSTOM",voice,"CALM",rate,"",engine,revision);
    }
}
