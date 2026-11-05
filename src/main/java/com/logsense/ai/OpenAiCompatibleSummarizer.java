package com.logsense.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.logsense.config.LogSenseProperties;
import com.logsense.processing.masking.PiiMasker;
import com.logsense.rca.RcaResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Calls any OpenAI-compatible {@code /v1/chat/completions} endpoint (OpenAI, Azure OpenAI proxies, Ollama,
 * vLLM, LM Studio). Falls back to the deterministic template if the LLM is unavailable.
 */
@Slf4j
public final class OpenAiCompatibleSummarizer implements AiSummarizer {

    private final RestClient restClient;
    private final LogSenseProperties.Ai config;
    private final TemplateSummarizer fallback;
    private final PiiMasker masker;

    public OpenAiCompatibleSummarizer(RestClient restClient, LogSenseProperties.Ai config,
                                      TemplateSummarizer fallback, PiiMasker masker) {
        this.restClient = restClient;
        this.config = config;
        this.fallback = fallback;
        this.masker = masker;
    }

    @Override
    public String summarize(RcaResult result) {
        Map<String, Object> request = Map.of(
                "model", config.model(),
                "temperature", config.temperature(),
                "messages", List.of(
                        Map.of("role", "system", "content", PromptBuilder.SYSTEM_PROMPT),
                        Map.of("role", "user", "content",
                                PromptBuilder.userPrompt(result, config.maxFindingsInPrompt(), masker))));
        try {
            JsonNode response = restClient.post()
                    .uri(config.chatPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            String content = response == null ? null
                    : response.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("Empty completion");
            }
            return masker.mask(content.trim());
        } catch (Exception e) {
            log.warn("LLM summarization failed ({}), falling back to template summary", e.getMessage());
            return "[AI unavailable - template summary] " + fallback.summarize(result);
        }
    }

    @Override
    public boolean isAiBacked() {
        return true;
    }

    @Override
    public String name() {
        return "openai-compatible:" + config.model();
    }
}
