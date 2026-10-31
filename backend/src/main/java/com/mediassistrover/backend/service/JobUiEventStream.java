package com.mediassistrover.backend.service;

import com.mediassistrover.backend.security.AccountPrincipal;
import com.mediassistrover.backend.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** SSE is a live hint; persisted REST Job detail is the reconnect source of truth. */
@Service @RequiredArgsConstructor
public class JobUiEventStream {
    private final JobUiSnapshotService snapshots;
    private final Clock clock;
    private final ConcurrentMap<UUID,CopyOnWriteArrayList<Subscription>> subscribers=new ConcurrentHashMap<>();
    private record Subscription(AccountPrincipal actor,SseEmitter emitter) { }

    public SseEmitter subscribe(UUID jobId,AccountPrincipal actor) {
        snapshots.snapshot(jobId,actor);
        long ttl=actor.tokenExpiresAt()==null?300_000:Math.min(300_000,Duration.between(Instant.now(clock),actor.tokenExpiresAt()).toMillis());
        SseEmitter emitter=new SseEmitter(Math.max(1,ttl));
        Subscription subscription=new Subscription(actor,emitter);
        subscribers.compute(jobId,(id,current)->{
            var list=current==null?new CopyOnWriteArrayList<Subscription>():current;
            if (list.size()>=64) throw new BusinessRuleException("Too many Job event subscriptions");
            list.add(subscription); return list;
        });
        Runnable remove=()->subscribers.computeIfPresent(jobId,(id,list)->{list.remove(subscription);return list.isEmpty()?null:list;});
        emitter.onCompletion(remove); emitter.onTimeout(remove); emitter.onError(error->remove.run());
        send(jobId,subscription);
        return emitter;
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void changed(RuntimeChanged event) {
        for (Subscription subscription:subscribers.getOrDefault(event.jobId(),new CopyOnWriteArrayList<>())) send(event.jobId(),subscription);
    }
    @org.springframework.context.event.EventListener
    public void preview(com.mediassistrover.backend.mapping.MappingPreviewService.Changed event) {
        for(Subscription subscription:subscribers.getOrDefault(event.jobId(),new CopyOnWriteArrayList<>()))synchronized(subscription) {
            try {
                snapshots.snapshot(event.jobId(),subscription.actor());
                subscription.emitter().send(SseEmitter.event().name("mapping-preview").data(event.preview()));
            } catch(Exception ex) {subscription.emitter().complete();subscribers.computeIfPresent(event.jobId(),(id,list)->{list.remove(subscription);return list.isEmpty()?null:list;});}
        }
    }
    public void heartbeat() {
        subscribers.forEach((jobId,list)->list.forEach(subscription->send(jobId,subscription)));
    }
    private void send(UUID jobId,Subscription subscription) {
        synchronized (subscription) {
            try {
                var detail=snapshots.snapshot(jobId,subscription.actor());
                subscription.emitter().send(SseEmitter.event().name("job-state").data(detail));
            } catch (Exception ex) {
                subscribers.computeIfPresent(jobId,(id,list)->{list.remove(subscription);return list.isEmpty()?null:list;});
                subscription.emitter().complete();
            }
        }
    }
}
