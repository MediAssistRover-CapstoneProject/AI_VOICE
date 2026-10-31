package com.mediassistrover.backend.mqtt;
/** Transport must be reconciled by HELLO/replay; durable work is kept pending without a negative ACK. */
public class MqttSessionUnavailableException extends RuntimeException {
    public MqttSessionUnavailableException(String message) {super(message);}
}
