package com.mediassistrover.backend.mqtt;

import java.util.*;
public final class MqttTopics {
    private MqttTopics() { }
    public record Topic(UUID robotId,String lane) { }
    public static String topic(UUID robotId,String lane) { return "mar/robots/"+robotId+"/"+lane; }
    public static Topic parse(String value) {
        String[] parts=value.split("/",-1);
        if ((parts.length!=4 && parts.length!=5) || !parts[0].equals("mar") || !parts[1].equals("robots"))
            throw new IllegalArgumentException("Invalid inbound MQTT topic");
        String lane=String.join("/",Arrays.copyOfRange(parts,3,parts.length));
        if (lane.equals("map-preview")) lane="mapping/preview";
        if (!Set.of("events","heartbeat","presence","mapping/preview","profile-acks","mapping/control-acks","camera/preview").contains(lane))
            throw new IllegalArgumentException("Invalid inbound MQTT topic");
        UUID id=UUID.fromString(parts[2]);
        if (!id.toString().equals(parts[2])) throw new IllegalArgumentException("Topic robot ID must be a canonical lowercase UUID");
        return new Topic(id,lane);
    }
}
