package com.mediassistrover.backend.mapping;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component @ConfigurationProperties("app.mapping") @Getter @Setter
public class MappingProperties {
    private boolean enabled;
    private String storageDir="./data/map-artifacts";
    private int maxArchiveBytes=16*1024*1024;
    private int maxUncompressedBytes=12*1024*1024;
    private int startTimeoutSec=45;
}
