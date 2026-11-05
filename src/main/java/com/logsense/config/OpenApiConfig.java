package com.logsense.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI logSenseOpenApi() {
        return new OpenAPI().info(new Info()
                .title("LogSense API")
                .description("AI Log Analysis & Root Cause Analysis Platform: upload multi-service logs, reconstruct "
                        + "transaction flows, detect failures and generate RCA reports.")
                .version("v1")
                .contact(new Contact().name("LogSense"))
                .license(new License().name("MIT")));
    }
}
