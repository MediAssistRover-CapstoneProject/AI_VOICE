package com.mediassistrover.backend.ai;
import lombok.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Component @ConfigurationProperties("app.ai") @Getter @Setter
public class AiProperties {
    private boolean sensingEnabled;
}
