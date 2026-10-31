package com.mediassistrover.backend;

import com.mediassistrover.backend.speech.OpenAiTtsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SpeechAudioApiIntegrationTest extends MySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean OpenAiTtsService speech;
    final String path="/api/v1/pi/speech/synthesize";
    final String body="{\"text\":\"Xin chÃ o\",\"languageCode\":\"vi\"}";
    @Test @WithMockUser(roles="ADMIN") void adminCanPrepareAndPoll() throws Exception {
        when(speech.request("Xin chÃ o","vi",150)).thenReturn(new OpenAiTtsService.Result("PENDING",null,null));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted())
            .andExpect(jsonPath("$.code").value("TTS_GENERATING"));
    }
    @Test @WithMockUser(roles="DEVICE") void deviceDownloadsWavAndHash() throws Exception {
        byte[] wave=new byte[]{1,2,3};
        when(speech.request("Xin chÃ o","vi",150)).thenReturn(new OpenAiTtsService.Result("READY",null,wave));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
            .andExpect(content().bytes(wave)).andExpect(header().string("X-Audio-SHA256",OpenAiTtsService.sha256(wave)));
    }
    @Test void anonymousCannotSpendProviderQuota() throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }
    @Test @WithMockUser(roles="STAFF") void staffCannotSynthesizeDirectly() throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="ADMIN") void invalidInputIsRejected() throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\" \",\"languageCode\":\"xx\"}"))
            .andExpect(status().isBadRequest());
    }
    @Test @WithMockUser(roles="ADMIN") void prepareReturnsJsonInsteadOfAudio() throws Exception {
        when(speech.request("Xin chÃ o","vi",150)).thenReturn(new OpenAiTtsService.Result("READY",null,new byte[]{1,2,3}));
        mvc.perform(post("/api/v1/pi/speech/prepare").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("READY"));
    }
    @Test @WithMockUser(roles="DEVICE") void deviceCannotUseAdminPrepare() throws Exception {
        mvc.perform(post("/api/v1/pi/speech/prepare").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
    }
    @Test void swaggerShowsCorrectTokensForPrepareAndAudio() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/pi/speech/prepare'].post.security[0].bearerAuth").isArray())
            .andExpect(jsonPath("$.paths['/api/v1/pi/speech/synthesize'].post.security[0].deviceBearerAuth").isArray())
            .andExpect(jsonPath("$.paths['/api/v1/pi/speech/synthesize'].post.security[1].bearerAuth").isArray());
    }
}
