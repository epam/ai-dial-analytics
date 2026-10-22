package com.epam.aidial.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AnalyticsDataAccessApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsDataAccessApplication.class, args);
    }
}
