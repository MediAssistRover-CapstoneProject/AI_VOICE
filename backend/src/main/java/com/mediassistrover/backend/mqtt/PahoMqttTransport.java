package com.mediassistrover.backend.mqtt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.*;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.net.ssl.*;
import java.util.concurrent.*;

@Component @RequiredArgsConstructor @Slf4j
public class PahoMqttTransport implements SmartLifecycle {
    private final com.mediassistrover.backend.mqtt.MqttProperties properties;
    private final MqttConnectionState state;
    private final ObjectProvider<MqttInboundRouter> router;
    private volatile MqttAsyncClient client;
    private volatile boolean running;
    private final MqttPublishQueue publications=new MqttPublishQueue();
    private final ScheduledExecutorService outbound=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"mar-mqtt-outbound");thread.setDaemon(true);return thread;});
    private final java.util.concurrent.atomic.AtomicBoolean outboundStarted=new java.util.concurrent.atomic.AtomicBoolean();
    private final ThreadPoolExecutor inbound=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(128),
            task->{Thread thread=new Thread(task,"mar-mqtt-inbound");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());

    public boolean connected() { return client!=null && client.isConnected() && state.connected(); }
    public static SSLSocketFactory tls(com.mediassistrover.backend.mqtt.MqttProperties properties) {
        try {
            TrustManager[] trust=null;KeyManager[] keys=null;
            if (properties.getTrustStore()!=null) {
                KeyStore store=KeyStore.getInstance("PKCS12");try(var input=java.nio.file.Files.newInputStream(properties.getTrustStore())) {store.load(input,chars(properties.getTrustStorePassword()));}
                var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(store);trust=factory.getTrustManagers();
            }
            if (properties.getKeyStore()!=null) {
                KeyStore store=KeyStore.getInstance("PKCS12");try(var input=java.nio.file.Files.newInputStream(properties.getKeyStore())) {store.load(input,chars(properties.getKeyStorePassword()));}
                var factory=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());factory.init(store,chars(properties.getKeyStorePassword()));keys=factory.getKeyManagers();
            }
            SSLContext context=SSLContext.getInstance("TLS");context.init(keys,trust,null);return context.getSocketFactory();
        } catch (Exception ex) {throw new IllegalArgumentException("Cannot initialize MQTT TLS stores",ex);}
    }
    private static char[] chars(String value) {return value==null?null:value.toCharArray();}
    @Override public synchronized void start() {
        if (running || !properties.isEnabled()) return;
        properties.validateConnection();
        try {
            client=new MqttAsyncClient(properties.getBrokerUri(),properties.getClientId(),new MemoryPersistence());
            client.setCallback(new MqttCallback() {
                public void disconnected(MqttDisconnectResponse response) {
                    state.disconnected();publications.failPending(new IllegalStateException("MQTT disconnected"));
                    log.warn("MQTT disconnected: clientId={} returnCode={} exceptionCode={}",properties.getClientId(),response.getReturnCode(),
                            response.getException()==null?null:response.getException().getReasonCode());
                }
                public void mqttErrorOccurred(MqttException error) {log.warn("MQTT client error: reason {}",error.getReasonCode());}
                public void deliveryComplete(IMqttToken token) { }
                public void authPacketArrived(int reason,MqttProperties packet) { }
                public void connectComplete(boolean reconnect,String uri) {
                    try {
                        client.subscribe(subscriptions(),null,new MqttActionListener() {
                            public void onSuccess(IMqttToken token) {
                                int[] reasons=token.getReasonCodes();
                                if (reasons==null || reasons.length!=subscriptions().length || java.util.Arrays.stream(reasons).anyMatch(code->code>=128)) {
                                    state.disconnected();log.warn("MQTT SUBACK denied a required topic; session remains unavailable");
                                } else {state.connectedNow();log.info("MQTT subscriptions ready: clientId={} connectionId={} reconnect={}",properties.getClientId(),state.connectionId(),reconnect);}
                            }
                            public void onFailure(IMqttToken token,Throwable error) {state.disconnected();log.warn("MQTT subscriptions denied; session remains unavailable");}
                        },new MqttProperties());
                    } catch (MqttException ex) {state.disconnected();log.warn("MQTT subscriptions failed: reason {}",ex.getReasonCode());}
                }
                public void messageArrived(String topic,MqttMessage message) {
                    byte[] bytes=message.getPayload().clone();int qos=message.getQos();boolean retained=message.isRetained();
                    try {inbound.execute(()->router.getObject().receive(topic,bytes,qos,retained));}
                    catch (RejectedExecutionException ex) {log.warn("MQTT inbound queue full; durable event receives no application ACK");}
                }
            });
            MqttConnectionOptions options=new MqttConnectionOptions();options.setUserName(properties.getUsername());options.setPassword(properties.getPassword().getBytes(StandardCharsets.UTF_8));
            options.setCleanStart(true);options.setSessionExpiryInterval(0L);options.setKeepAliveInterval(10);options.setConnectionTimeout(10);
            options.setAutomaticReconnect(true);options.setAutomaticReconnectDelay(1000,5000);options.setMaximumPacketSize(1_048_576L);
            options.setSocketFactory(tls(properties));options.setHttpsHostnameVerificationEnabled(true);
            running=true;
            if(outboundStarted.compareAndSet(false,true)) outbound.scheduleWithFixedDelay(this::drainPublication,0,10,TimeUnit.MILLISECONDS);
            client.connect(options,null,new MqttActionListener() {
                public void onSuccess(IMqttToken token) { }
                public void onFailure(IMqttToken token,Throwable error) {state.disconnected();log.warn("MQTT initial connection failed; scheduler will retry");}
            });
        } catch (MqttException ex) {running=false;throw new IllegalStateException("Cannot start MQTT client",ex);}
    }
    public CompletableFuture<Void> publish(String topic,byte[] bytes,int qos) {
        if (!connected()) return CompletableFuture.failedFuture(new IllegalStateException("MQTT is disconnected"));
        var payload=new MqttWire().parse(bytes);
        boolean priority="SERVER_HEARTBEAT".equals(payload.path("messageType").asText()) || "CANCEL_STEP".equals(payload.path("commandType").asText())
                || "MAPPING_CONTROL".equals(payload.path("messageType").asText()) && payload.path("linearX").asDouble()==0 && payload.path("angularZ").asDouble()==0;
        return publications.offer(topic,bytes,qos,priority,properties.getPublishQueueCapacity());
    }
    /** Preserve the broker's retain flag; ignore stored retained messages when subscribing. */
    public static MqttSubscription[] subscriptions() {
        String[] lanes={"events","heartbeat","presence","map-preview","mapping/preview","profile-acks","mapping/control-acks","camera/preview"};
        MqttSubscription[] subscriptions=new MqttSubscription[lanes.length];
        for(int i=0;i<lanes.length;i++) {
            var value=new MqttSubscription("mar/robots/+/"+lanes[i],java.util.Set.of("heartbeat","map-preview","mapping/preview","camera/preview").contains(lanes[i])?0:1);
            value.setRetainAsPublished(true);value.setRetainHandling(2);subscriptions[i]=value;
        }
        return subscriptions;
    }
    private void drainPublication() {
        if(!connected()) return;
        var pending=publications.poll(System.nanoTime(),properties.getPublishRatePerSecond());if(pending==null)return;
        try {
            var payload=new MqttWire().parse(pending.bytes());
            if(payload.has("expiresAt") && !java.time.Instant.parse(payload.path("expiresAt").asText()).isAfter(java.time.Instant.now()))
                throw new IllegalStateException("MQTT command expired in publish queue");
            client.publish(pending.topic(),pending.bytes(),pending.qos(),false,null,new MqttActionListener() {
                public void onSuccess(IMqttToken token) {pending.result().complete(null);}
                public void onFailure(IMqttToken token,Throwable error) {pending.result().completeExceptionally(error);}
            });
        } catch (Exception ex) {pending.result().completeExceptionally(ex);}
    }
    public synchronized void retryInitialConnection() {
        if (properties.isEnabled() && !connected() && client!=null && !client.isConnected()) {
            try {client.reconnect();} catch (MqttException ex) {log.debug("MQTT connection retry pending: reason {}",ex.getReasonCode());}
        }
    }
    @Override public synchronized void stop() {
        state.disconnected();running=false;
        publications.failPending(new IllegalStateException("MQTT transport stopped"));
        if (client!=null) {try {client.disconnectForcibly(1000L,1000L,true);client.close(true);} catch (MqttException ex) {log.debug("MQTT close: reason {}",ex.getReasonCode());}client=null;}
    }
    @Override public boolean isRunning() {return running;}
    @jakarta.annotation.PreDestroy public void destroy() {stop();inbound.shutdownNow();outbound.shutdownNow();}
    @Override public boolean isAutoStartup() {return properties.isEnabled();}
    @Override public int getPhase() {return Integer.MAX_VALUE-100;}
}
