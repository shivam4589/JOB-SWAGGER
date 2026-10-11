package com.example.jobqueue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JobQueueApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobQueueApplication.class, args);
    }
}
