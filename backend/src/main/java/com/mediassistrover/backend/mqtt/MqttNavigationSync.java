package com.mediassistrover.backend.mqtt;

import com.mediassistrover.backend.service.NavigationProfileService;
import com.mediassistrover.backend.validation.MqttContractValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

/** Rebuild the catalog only while idle; retry until Pi acknowledges the current session/hash. */
@Component @RequiredArgsConstructor @Slf4j
public class MqttNavigationSync {
    private final MqttProperties properties;
    private final PahoMqttTransport transport;
    private final NavigationProfileService profiles;
    private final MqttContractValidator contracts;
    private final MqttWire wire;
    @Scheduled(fixedDelay=5000)
    public void synchronizeProfiles() {
        if(!properties.isEnabled() || !properties.isNavigationProfileSyncEnabled() || !transport.connected()) return;
        for(var robot:properties.getRobotIds()) {
            try {
                var message=profiles.pendingMqtt(robot);
                if(message!=null) {
                    contracts.response(message);
                    transport.publish(MqttTopics.topic(robot,"event-acks"),wire.bytes(message),1)
                            .exceptionally(error->{log.debug("Navigation profile publication will retry for {}",robot);return null;});
                }
            } catch(RuntimeException ex) { log.debug("Navigation profile pending for {}: {}",robot,ex.getMessage()); }
        }
    }
}
