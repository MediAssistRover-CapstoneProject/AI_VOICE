package com.mediassistrover.backend.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.assistant.OpenAiGateway;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded asynchronous synthesis: repeated requests share one provider call and disk cache. */
@Service
public class OpenAiTtsService implements AutoCloseable {
    public record Result(String state, String code, byte[] audio) { }
    private record Work(Future<byte[]> future, long created) { }
    private final boolean enabled;
    private final String apiKey, voice, model, instructions;
    private final double speed;
    private final Path directory;
    private final URI endpoint;
    private final OpenAiGateway realtimeGateway;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"openai-tts");t.setDaemon(true);return t;});
    private final Map<String,Work> pending=new HashMap<>();
    static final int MAX_BYTES=4*1024*1024;

    @org.springframework.beans.factory.annotation.Autowired
    public OpenAiTtsService(@Value("${app.tts.enabled:false}") boolean enabled,
                           @Value("${app.tts.openai-api-key:}") String key,
                           @Value("${app.tts.voice:coral}") String voice,
                           @Value("${app.tts.speed:1.0}") double speed,
                           @Value("${app.tts.model:gpt-realtime-2.1-mini}") String model,
                           @Value("${app.tts.instructions:Speak Vietnamese with a clear Northern Vietnamese accent and a calm adult female tone. Read only the supplied text.}") String instructions,
                           @Value("${app.tts.cache-dir:./data/speech}") String directory, ObjectMapper json,
                           OpenAiGateway realtimeGateway) {
        this(enabled,key,voice,speed,model,instructions,Path.of(directory),json,URI.create("https://api.openai.com/v1/audio/speech"),realtimeGateway);
    }
    OpenAiTtsService(boolean enabled,String key,String voice,double speed,String model,String instructions,Path directory,ObjectMapper json,URI endpoint) {
        this(enabled,key,voice,speed,model,instructions,directory,json,endpoint,null);
    }
    OpenAiTtsService(boolean enabled,String key,String voice,double speed,String model,String instructions,Path directory,ObjectMapper json,URI endpoint,OpenAiGateway realtimeGateway) {
        this.enabled=enabled;this.apiKey=key;this.voice=voice;this.speed=speed;this.directory=directory;
        this.json=json;this.endpoint=endpoint;this.model=model;this.instructions=instructions;this.realtimeGateway=realtimeGateway;
        if(!Set.of("alloy","ash","ballad","coral","echo","fable","nova","onyx","sage","shimmer","verse","marin","cedar").contains(voice)
                ||!Double.isFinite(speed)||speed<0.25||speed>4.0||model.isBlank()||instructions.length()>1000)
            throw new IllegalArgumentException("Invalid OpenAI TTS configuration");
    }
    public synchronized Result request(String text,String language) {
        return request(text,language,150);
    }
    public synchronized Result request(String text,String language,int speedWpm) {
        if(!enabled)return new Result("FAILED","TTS_DISABLED",null);
        if(apiKey.isBlank())return new Result("FAILED","TTS_API_KEY_MISSING",null);
        if(!Set.of("vi","en").contains(language))return new Result("FAILED","TTS_LANGUAGE_UNSUPPORTED",null);
        if(text==null||text.isBlank()||text.length()<1||text.length()>500)
            return new Result("FAILED","TTS_TEXT_INVALID",null);
        if(speedWpm<80||speedWpm>220)return new Result("FAILED","TTS_SPEED_INVALID",null);
        double renderSpeed=Math.max(0.25,Math.min(4.0,speed*speedWpm/150.0));
        String id=sha256(("openai\n"+model+"\n"+voice+"\n"+renderSpeed+"\n"+language+"\n"+instructions+"\n"+text).getBytes(StandardCharsets.UTF_8));
        Path file=directory.resolve(id+".wav");
        try {
            if(Files.isRegularFile(file)) {
                if(Files.size(file)>MAX_BYTES)Files.delete(file);
                else {byte[] audio=Files.readAllBytes(file);if(wav(audio))return new Result("READY",null,audio);Files.delete(file);}
            }
            Work work=pending.get(id);
            if(work!=null&&work.future.isDone()) {
                try {return new Result("READY",null,work.future.get());}
                catch(Exception e){return new Result("FAILED",safeCode(e),null);}
                finally {pending.remove(id);}
            }
            long now=System.currentTimeMillis();
            pending.entrySet().removeIf(e->e.getValue().future.isDone()&&now-e.getValue().created>180_000);
            if(work==null) {
                if(pending.size()>=128)return new Result("FAILED","TTS_BUSY",null);
                try {pending.put(id,new Work(executor.submit(()->generate(text,language,file,renderSpeed)),now));}
                catch(RejectedExecutionException e){return new Result("FAILED","TTS_BUSY",null);}
            }
            return new Result("PENDING",null,null);
        } catch(IOException e){return new Result("FAILED","TTS_CACHE_ERROR",null);}
    }
    private byte[] generate(String text,String language,Path file,double renderSpeed) throws Exception {
        if(model.startsWith("gpt-realtime")){
            if(realtimeGateway==null)throw new IOException("TTS_REALTIME_GATEWAY_UNAVAILABLE");
            byte[] bytes=realtimeGateway.synthesizeWav(text,language);
            if(!wav(bytes))throw new IOException("TTS_INVALID_WAV");
            Files.createDirectories(directory);Path temporary=Files.createTempFile(directory,"tts-",".tmp");
            try{Files.write(temporary,bytes);Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}trimCache();return bytes;
        }
        var body=json.createObjectNode().put("model",model).put("voice",voice).put("input",text)
            .put("response_format","wav").put("speed",renderSpeed);
        if(!Set.of("tts-1","tts-1-hd").contains(model))body.put("instructions",language.equals("vi")?instructions:"Speak clearly in English. Read only the supplied text.");
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60))
            .header("Authorization","Bearer "+apiKey).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()==401||response.statusCode()==403)throw new IOException("TTS_PROVIDER_AUTH_FAILED");
        if(response.statusCode()==429)throw new IOException("TTS_PROVIDER_QUOTA");
        if(response.statusCode()==404)throw new IOException("TTS_MODEL_UNAVAILABLE");
        if(response.statusCode()!=200)throw new IOException("TTS_PROVIDER_REJECTED");
        byte[] bytes=response.body();
        if(!wav(bytes))throw new IOException("TTS_INVALID_WAV");
        Files.createDirectories(directory);
        Path temporary=Files.createTempFile(directory,"tts-",".tmp");
        try {Files.write(temporary,bytes);Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        finally {Files.deleteIfExists(temporary);}
        trimCache();
        return bytes;
    }
    private void trimCache() throws IOException {
        try(var files=Files.list(directory)) {
            var entries=files.filter(p->p.toString().endsWith(".wav"))
                .sorted(Comparator.comparingLong(p->{try{return Files.getLastModifiedTime(p).toMillis();}catch(IOException e){return 0L;}})).toList();
            for(int i=0;i<entries.size()-128;i++)Files.deleteIfExists(entries.get(i));
        }
    }
    static boolean wav(byte[] a) {return a.length>44&&a.length<=MAX_BYTES&&a[0]=='R'&&a[1]=='I'&&a[2]=='F'&&a[3]=='F'
        &&a[8]=='W'&&a[9]=='A'&&a[10]=='V'&&a[11]=='E';}
    public static String sha256(byte[] a) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(a));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static String safeCode(Exception e) {
        Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();
        String message=cause.getMessage();
        return message!=null&&message.matches("TTS_[A-Z_]+")?message:"TTS_PROVIDER_UNAVAILABLE";
    }
    @PreDestroy public void close(){executor.shutdownNow();}
}
