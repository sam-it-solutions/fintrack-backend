package com.fintrack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fintrack.ai.gemini")
public record GeminiProperties(
    String apiKey,
    String baseUrl,
    String model,
    Boolean enabled
) {}
