package com.sheltr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SheltrBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(SheltrBackendApplication.class, args);
    }
}
