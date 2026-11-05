package com.logsense.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Raw log text to analyse (for small snippets; use multipart upload for files)")
public record AnalyzeTextRequest(
        @Schema(example = "order-service.log")
        @NotBlank @Size(max = 255) @Pattern(regexp = "[\\w.-]+", message = "must contain only letters, digits, '.', '_' or '-'")
        String sourceName,
        @NotBlank @Size(max = 5_000_000)
        String content) {
}
