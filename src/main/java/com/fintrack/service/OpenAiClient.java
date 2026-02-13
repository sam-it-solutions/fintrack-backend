package com.fintrack.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.config.OpenAiProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenAiClient {
  private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);
  private static final Pattern CATEGORY_PATTERN = Pattern.compile("\"category\"\\s*:\\s*\"([^\"]+)\"");

  private final OpenAiProperties properties;
  private final ObjectMapper objectMapper;
  private final RestClient restClient;
  private final AppSettingsService appSettingsService;

  public OpenAiClient(OpenAiProperties properties, ObjectMapper objectMapper, AppSettingsService appSettingsService) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.appSettingsService = appSettingsService;
    String baseUrl = properties.baseUrl() == null || properties.baseUrl().isBlank()
        ? "https://api.openai.com"
        : properties.baseUrl();
    this.restClient = RestClient.builder().baseUrl(baseUrl).build();
  }

  public String classify(String systemPrompt, String userPrompt, List<String> allowedCategories) {
    if (!appSettingsService.isAiAvailable()) {
      return null;
    }
    if (properties.apiKey() == null || properties.apiKey().isBlank()) {
      return null;
    }
    String configuredModel = appSettingsService.getAiModel();
    String model = configuredModel == null || configuredModel.isBlank()
        ? "gpt-4.1-mini"
        : configuredModel;

    Map<String, Object> body = Map.of(
        "model", model,
        "messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userPrompt)
        ),
        "temperature", 0.2,
        "max_tokens", 60
    );

    try {
      JsonNode response = restClient.post()
          .uri("/v1/chat/completions")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(JsonNode.class);

      String content = response == null
          ? null
          : response.path("choices").path(0).path("message").path("content").asText(null);
      return extractCategory(content, allowedCategories);
    } catch (Exception ex) {
      handleAiFailure(ex);
      return null;
    }
  }

  private void handleAiFailure(Exception ex) {
    String message = ex.getMessage() == null ? "" : ex.getMessage();
    String lower = message.toLowerCase();
    if (lower.contains("insufficient_quota") || lower.contains("exceeded your current quota")) {
      appSettingsService.recordAiFailure(message, Duration.ofHours(24));
      log.warn("OpenAI categorization paused for 24h due to quota: {}", message);
      return;
    }
    if (lower.contains("rate limit") || lower.contains("too many requests") || lower.contains("rate_limit")) {
      appSettingsService.recordAiFailure(message, Duration.ofMinutes(15));
      log.warn("OpenAI categorization paused for 15m due to rate limit: {}", message);
      return;
    }
    log.warn("OpenAI categorization failed: {}", message);
  }

  private String extractCategory(String content, List<String> allowedCategories) {
    if (content == null || content.isBlank()) {
      return null;
    }
    String trimmed = content.trim();
    try {
      JsonNode node = objectMapper.readTree(trimmed);
      String category = node.path("category").asText(null);
      return normalizeCategory(category, allowedCategories);
    } catch (Exception ignored) {
      // fall through
    }
    Matcher matcher = CATEGORY_PATTERN.matcher(trimmed);
    if (matcher.find()) {
      return normalizeCategory(matcher.group(1), allowedCategories);
    }
    return normalizeCategory(trimmed.replace("\"", ""), allowedCategories);
  }

  private String normalizeCategory(String category, List<String> allowedCategories) {
    if (category == null || category.isBlank() || allowedCategories == null || allowedCategories.isEmpty()) {
      return null;
    }
    for (String allowed : allowedCategories) {
      if (allowed.equalsIgnoreCase(category.trim())) {
        return allowed;
      }
    }
    return null;
  }
}
