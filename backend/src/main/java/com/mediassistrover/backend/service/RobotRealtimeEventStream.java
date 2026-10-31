package com.mediassistrover.backend.service;

import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.security.AccountPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service @RequiredArgsConstructor
public class RobotRealtimeEventStream {
    public record Changed(UUID robotId) { }
    private record Subscription(AccountPrincipal actor,SseEmitter emitter) { }
    private final RobotRealtimeSnapshotService snapshots;
    private final Clock clock;
    private final ConcurrentMap<UUID,CopyOnWriteArrayList<Subscription>> subscribers=new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID robotId,AccountPrincipal actor) {
        snapshots.snapshot(robotId);
        long ttl=actor.tokenExpiresAt()==null?300_000:Math.min(300_000,Duration.between(Instant.now(clock),actor.tokenExpiresAt()).toMillis());
        SseEmitter emitter=new SseEmitter(Math.max(1,ttl));Subscription subscription=new Subscription(actor,emitter);
        subscribers.compute(robotId,(id,current)->{var list=current==null?new CopyOnWriteArrayList<Subscription>():current;
            if(list.size()>=64)throw new BusinessRuleException("Too many robot realtime subscriptions");list.add(subscription);return list;});
        Runnable remove=()->subscribers.computeIfPresent(robotId,(id,list)->{list.remove(subscription);return list.isEmpty()?null:list;});
        emitter.onCompletion(remove);emitter.onTimeout(remove);emitter.onError(error->remove.run());send(robotId,subscription);return emitter;
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void changed(Changed event){for(Subscription subscription:subscribers.getOrDefault(event.robotId(),new CopyOnWriteArrayList<>()))send(event.robotId(),subscription);}
    /** Periodic state event doubles as SSE keepalive and exposes heartbeat/pose freshness transitions. */
    public void heartbeat(){subscribers.forEach((robotId,list)->list.forEach(subscription->send(robotId,subscription)));}
    private void send(UUID robotId,Subscription subscription){synchronized(subscription){try{subscription.emitter().send(SseEmitter.event().name("robot-state").data(snapshots.snapshot(robotId)));}catch(Exception ex){subscribers.computeIfPresent(robotId,(id,list)->{list.remove(subscription);return list.isEmpty()?null:list;});subscription.emitter().complete();}}}
}
