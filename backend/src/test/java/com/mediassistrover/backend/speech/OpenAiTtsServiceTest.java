package com.mediassistrover.backend.speech;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.mediassistrover.backend.assistant.OpenAiGateway;
import com.mediassistrover.backend.assistant.VoiceSettingsService;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class OpenAiTtsServiceTest {
    @TempDir Path directory;
    HttpServer server;OpenAiTtsService service;
    AtomicInteger submissions=new AtomicInteger();byte[] wav=new byte[48];
    @BeforeEach void start() throws Exception {
        System.arraycopy("RIFF".getBytes(),0,wav,0,4);System.arraycopy("WAVE".getBytes(),0,wav,8,4);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/speech",e->{
            submissions.incrementAndGet();
            assertThat(e.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-key");
            var body=new ObjectMapper().readTree(e.getRequestBody());
            assertThat(body.path("model").asText()).isEqualTo("gpt-4o-mini-tts");
            assertThat(body.path("voice").asText()).isEqualTo("coral");
            assertThat(body.path("response_format").asText()).isEqualTo("wav");
            assertThat(body.path("input").asText()).isEqualTo("Xin chào");
            e.sendResponseHeaders(200,wav.length);e.getResponseBody().write(wav);e.close();
        });server.start();service=create(true,"test-key","/speech");
    }
    OpenAiTtsService create(boolean enabled,String key,String path) {
        return new OpenAiTtsService(enabled,key,"coral",1.0,"gpt-4o-mini-tts","Speak Vietnamese clearly.",directory,new ObjectMapper(),
            URI.create("http://127.0.0.1:"+server.getAddress().getPort()+path));
    }
    OpenAiTtsService.Result waitFor() throws Exception {
        for(int i=0;i<100;i++){var r=service.request("Xin chào","vi");if(!r.state().equals("PENDING"))return r;Thread.sleep(30);}
        throw new AssertionError("TTS did not finish");
    }
    @AfterEach void stop(){service.close();server.stop(0);}
    @Test void generatesOnceAndReusesPersistentCache() throws Exception {
        service.request("Xin chào","vi");assertThat(waitFor().audio()).isEqualTo(wav);
        service.close();service=create(true,"test-key","/speech");
        assertThat(service.request("Xin chào","vi").audio()).isEqualTo(wav);assertThat(submissions.get()).isEqualTo(1);
    }
    @Test void validatesInputBeforeProviderCall() {
        assertThat(service.request("hello","xx").code()).isEqualTo("TTS_LANGUAGE_UNSUPPORTED");
        assertThat(service.request(" ","vi").code()).isEqualTo("TTS_TEXT_INVALID");assertThat(submissions.get()).isZero();
    }
    @Test void missingConfigurationIsExplicit() {
        try(var disabled=create(false,"","/speech")){assertThat(disabled.request("Xin chào","vi").code()).isEqualTo("TTS_DISABLED");}
        try(var missing=create(true,"","/speech")){assertThat(missing.request("Xin chào","vi").code()).isEqualTo("TTS_API_KEY_MISSING");}
    }
    @Test void invalidAudioCannotBeCached() throws Exception {
        server.createContext("/bad",e->{byte[] b="not-a-wave".getBytes();e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});
        service.close();service=create(true,"test-key","/bad");assertThat(waitFor().code()).isEqualTo("TTS_INVALID_WAV");
    }
    @Test void authenticationErrorIsSanitized() throws Exception {
        server.createContext("/denied",e->{e.sendResponseHeaders(401,-1);e.close();});
        service.close();service=create(true,"test-key","/denied");assertThat(waitFor().code()).isEqualTo("TTS_PROVIDER_AUTH_FAILED");
    }
    @Test void quotaErrorDoesNotRetryAndSpendAgain() throws Exception {
        server.createContext("/quota",e->{e.sendResponseHeaders(429,-1);e.close();});
        service.close();service=create(true,"test-key","/quota");assertThat(waitFor().code()).isEqualTo("TTS_PROVIDER_QUOTA");
    }
    @Test void changingVoiceStyleOrSpeedUsesNewAudioWhileBrowserOnlyChangesReuseCache() throws Exception {
        var received = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        server.createContext("/dynamic",e->{
            received.set(new ObjectMapper().readTree(e.getRequestBody()));submissions.incrementAndGet();
            e.sendResponseHeaders(200,wav.length);e.getResponseBody().write(wav);e.close();
        });
        service.close();service=create(true,"test-key","/dynamic");
        var settings=mock(VoiceSettingsService.class);
        ReflectionTestUtils.setField(service,"voiceSettings",settings);
        when(settings.current()).thenReturn(new VoiceSettingsService.Settings("FEMALE","coral","CALM",1,"","BROWSER",0));
        assertThat(waitFor().audio()).isEqualTo(wav);
        when(settings.current()).thenReturn(new VoiceSettingsService.Settings("MALE","cedar","WARM",1.25,"","CLOUD",1));
        assertThat(waitFor().audio()).isEqualTo(wav);
        assertThat(submissions.get()).isEqualTo(2);
        assertThat(received.get().path("voice").asText()).isEqualTo("cedar");
        assertThat(received.get().path("speed").asDouble()).isEqualTo(1.25);
        assertThat(received.get().path("instructions").asText()).contains("warm");
        when(settings.current()).thenReturn(new VoiceSettingsService.Settings("MALE","cedar","WARM",1.25,"new-browser-voice","BROWSER",2));
        assertThat(service.request("Xin chào","vi").state()).isEqualTo("READY");
        assertThat(submissions.get()).isEqualTo(2);
    }
    @Test void realtimeUsesItsConfiguredModelAndClampsCombinedSpeechSpeed() throws Exception {
        service.close();
        var gateway=mock(OpenAiGateway.class);
        var settings=mock(VoiceSettingsService.class);
        when(settings.current()).thenReturn(new VoiceSettingsService.Settings("MALE","cedar","FORMAL",1.5,"","CLOUD",1));
        when(gateway.synthesizeWav(eq("Xin chào"),eq("vi"),any(),eq("gpt-realtime-2.1-mini"))).thenReturn(wav);
        service=new OpenAiTtsService(true,"test-key","coral",1,"gpt-realtime-2.1-mini","",directory,new ObjectMapper(),URI.create("http://unused"),gateway);
        ReflectionTestUtils.setField(service,"voiceSettings",settings);
        for(int i=0;i<100;i++){
            var result=service.request("Xin chào","vi",220);
            if(!result.state().equals("PENDING")){assertThat(result.audio()).isEqualTo(wav);break;}
            Thread.sleep(30);
        }
        var captured=org.mockito.ArgumentCaptor.forClass(VoiceSettingsService.Settings.class);
        verify(gateway).synthesizeWav(eq("Xin chào"),eq("vi"),captured.capture(),eq("gpt-realtime-2.1-mini"));
        assertThat(captured.getValue().rate()).isEqualTo(1.5);
        assertThat(captured.getValue().cloudVoice()).isEqualTo("cedar");
    }
}
