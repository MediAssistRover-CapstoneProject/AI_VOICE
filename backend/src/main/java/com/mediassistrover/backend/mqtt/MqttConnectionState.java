package com.mediassistrover.backend.mqtt;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class MqttConnectionState {
    private final UUID instanceId=UUID.randomUUID();
    private volatile UUID connectionId;
    public UUID instanceId() { return instanceId; }
    public UUID connectionId() { return connectionId; }
    public boolean connected() { return connectionId!=null; }
    public void connectedNow() { connectionId=UUID.randomUUID(); }
    public void disconnected() { connectionId=null; }
}
