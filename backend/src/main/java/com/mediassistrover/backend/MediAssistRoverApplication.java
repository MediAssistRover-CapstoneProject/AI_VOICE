package com.mediassistrover.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MediAssistRoverApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediAssistRoverApplication.class, args);
    }
}
