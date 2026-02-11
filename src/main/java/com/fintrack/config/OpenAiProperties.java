package com.fintrack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fintrack.ai.openai")
public record OpenAiProperties(
    String apiKey,
    String baseUrl,
    String model,
    Boolean enabled
) {}
