package com.mediassistrover.backend.mqtt;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Bounded, evenly spaced publications. Replays remain in the durable outbox on failure. */
final class MqttPublishQueue {
    record Pending(String topic, byte[] bytes, int qos, String key, CompletableFuture<Void> result) { }
    private final ArrayDeque<Pending> urgent=new ArrayDeque<>(), normal=new ArrayDeque<>();
    private final Map<String,Pending> waiting=new HashMap<>();
    private long nextPublishNanos;

    synchronized CompletableFuture<Void> offer(String topic, byte[] bytes, int qos, boolean priority, int capacity) {
        String key=topic+"\u0000"+Base64.getEncoder().encodeToString(bytes);
        Pending duplicate=waiting.get(key);
        if(duplicate!=null) return duplicate.result();
        if(waiting.size()>=capacity) {
            if(!priority || normal.isEmpty()) return CompletableFuture.failedFuture(new IllegalStateException("MQTT publish queue is full; retry from durable outbox"));
            Pending deferred=normal.removeLast();waiting.remove(deferred.key());
            deferred.result().completeExceptionally(new IllegalStateException("MQTT publication deferred for heartbeat/cancel; retry from durable outbox"));
        }
        Pending value=new Pending(topic,bytes.clone(),qos,key,new CompletableFuture<>());
        waiting.put(key,value);(priority?urgent:normal).addLast(value);
        return value.result();
    }
    synchronized Pending poll(long now,int rate) {
        if(now<nextPublishNanos || waiting.isEmpty()) return null;
        Pending value=urgent.isEmpty()?normal.removeFirst():urgent.removeFirst();waiting.remove(value.key());
        // No token burst after an idle interval, including reconnect/replay.
        nextPublishNanos=now+1_000_000_000L/rate;
        return value;
    }
    synchronized void failPending(Throwable error) {
        List<Pending> abandoned=new ArrayList<>(waiting.values());waiting.clear();urgent.clear();normal.clear();
        abandoned.forEach(value->value.result().completeExceptionally(error));
    }
}
