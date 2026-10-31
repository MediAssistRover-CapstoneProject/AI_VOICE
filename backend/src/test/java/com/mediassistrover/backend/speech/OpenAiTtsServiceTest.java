package com.mediassistrover.backend.speech;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
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
}
