package com.logsense.config;

import com.logsense.ai.OpenAiCompatibleSummarizer;
import com.logsense.ai.TemplateSummarizer;
import com.logsense.processing.masking.PiiMasker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Registers the LLM-backed summarizer only when {@code logsense.ai.enabled=true}; otherwise the deterministic
 * {@link TemplateSummarizer} is the only {@code AiSummarizer} bean.
 */
@Configuration
public class AiConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "logsense.ai", name = "enabled", havingValue = "true")
    public OpenAiCompatibleSummarizer openAiCompatibleSummarizer(RestClient.Builder builder,
                                                                 LogSenseProperties properties,
                                                                 TemplateSummarizer fallback,
                                                                 PiiMasker masker) {
        LogSenseProperties.Ai ai = properties.ai();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) ai.timeout().toMillis());
        requestFactory.setReadTimeout((int) ai.timeout().toMillis());
        RestClient.Builder configured = builder.baseUrl(ai.baseUrl()).requestFactory(requestFactory);
        if (ai.apiKey() != null && !ai.apiKey().isBlank()) {
            configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + ai.apiKey());
        }
        return new OpenAiCompatibleSummarizer(configured.build(), ai, fallback, masker);
    }
}
