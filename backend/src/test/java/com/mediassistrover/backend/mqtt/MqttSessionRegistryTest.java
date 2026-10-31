package com.mediassistrover.backend.mqtt;

import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.mapping.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.OrchestrationWorkService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MqttSessionRegistryTest {
    private final MqttConnectionState connection=new MqttConnectionState();
    private final RobotMqttSessionRepository sessions=mock(RobotMqttSessionRepository.class);
    private final RobotRepository robots=mock(RobotRepository.class);
    private final MappingLifecycleService mapping=mock(MappingLifecycleService.class);
    private final MqttSessionRegistry registry=new MqttSessionRegistry(sessions,robots,
            mock(JobRepository.class),mock(JobStepRepository.class),mock(RobotCommandOutboxRepository.class),
            mock(FacilityMapRepository.class),mock(RobotLocationHistoryRepository.class),mock(RobotStatusHistoryRepository.class),
            mock(OrchestrationWorkService.class),mock(MqttJobSafety.class),connection,new MqttProperties(),Clock.systemUTC(),
            mock(RobotNavigationProfileRepository.class),mapping,mock(MappingPreviewService.class),mock(ApplicationEventPublisher.class),
            mock(com.mediassistrover.backend.service.NavigationSessionService.class),mock(MappingCompletionService.class));

    @Test void disconnectedConnectionNeverOwnsAPersistedSession() {
        connection.connectedNow();
        var session=new RobotMqttSession();session.setHandshakeAccepted(true);
        session.setOwnerInstanceId(connection.instanceId());session.setConnectionId(connection.connectionId());
        assertThat(registry.owned(session)).isTrue();
        connection.disconnected();
        assertThat(registry.owned(session)).isFalse();
        connection.connectedNow();
        assertThat(registry.owned(session)).isFalse();
    }

    @Test void disconnectDuringHelloLeavesTheDurableMessageRetryableInsteadOfSavingNullConnection() {
        interruptedHello(false);
    }

    @Test void reconnectDuringHelloCannotBindTheOldMessageToTheNewConnection() {
        interruptedHello(true);
    }

    private void interruptedHello(boolean reconnect) {
        connection.connectedNow();UUID robotId=UUID.randomUUID();
        when(robots.findByIdForUpdate(robotId)).thenAnswer(invocation->{
            connection.disconnected();if(reconnect)connection.connectedNow();
            return Optional.of(new Robot());
        });
        when(mapping.telemetryMatches(eq(robotId),any())).thenReturn(true);
        var payload=new MqttWire().object().put("runtimeVersion","test");payload.putArray("states");
        var hello=new MqttWire.Envelope("MAR-STEP-2",UUID.randomUUID(),null,null,robotId,null,null,"HELLO",
                Instant.now().toString(),UUID.randomUUID(),1,UUID.randomUUID(),payload);
        assertThatThrownBy(()->registry.hello(hello,List.of())).isInstanceOf(MqttSessionUnavailableException.class)
                .hasMessageContaining("connection changed during HELLO");
        verify(sessions,never()).save(any());
    }
}
