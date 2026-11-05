package com.logsense;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LogSenseApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogSenseApplication.class, args);
    }
}
