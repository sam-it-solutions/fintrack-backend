package com.fintrack.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.config.GeminiProperties;
import com.fintrack.dto.AiKeyTestResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OpenAiClient {
  private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);
  private static final Pattern CATEGORY_PATTERN = Pattern.compile("\\\"category\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
  private static final Pattern RETRY_DELAY_PATTERN = Pattern.compile("\\\"retryDelay\\\"\\s*:\\s*\\\"(\\d+)(?:\\.\\d+)?s\\\"");
  private static final Duration MIN_REQUEST_SPACING = Duration.ofSeconds(2);
  private static final Duration QUOTA_COOLDOWN = Duration.ofHours(24);
  private static final Duration DEFAULT_RATE_LIMIT_COOLDOWN = Duration.ofMinutes(15);
  private static final Duration DEFAULT_QUOTA_COOLDOWN = Duration.ofMinutes(10);

  private final GeminiProperties properties;
  private final ObjectMapper objectMapper;
  private final RestClient restClient;
  private final AppSettingsService appSettingsService;
  private final Object requestLock = new Object();
  private volatile long nextAllowedRequestAtMs = 0L;

  public OpenAiClient(GeminiProperties properties, ObjectMapper objectMapper, AppSettingsService appSettingsService) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.appSettingsService = appSettingsService;
    String baseUrl = properties.baseUrl() == null || properties.baseUrl().isBlank()
        ? "https://generativelanguage.googleapis.com"
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

    Map<String, Object> body = Map.of(
        "contents", List.of(
            Map.of("parts", List.of(Map.of("text", buildPrompt(systemPrompt, userPrompt))))
        ),
        "generationConfig", Map.of(
            "temperature", 0.2,
            "maxOutputTokens", 80,
            "candidateCount", 1
        )
    );

    String model = resolveModel();

    try {
      throttleRequests();
      JsonNode response = restClient.post()
          .uri(uriBuilder -> uriBuilder
              .path("/v1beta/models/{model}:generateContent")
              .queryParam("key", properties.apiKey())
              .build(model))
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(JsonNode.class);

      String content = extractGeminiText(response);
      return extractCategory(content, allowedCategories);
    } catch (RestClientResponseException ex) {
      handleAiFailure(ex);
      return null;
    } catch (Exception ex) {
      handleAiFailure(ex);
      return null;
    }
  }

  public AiKeyTestResponse testApiKey() {
    if (properties.apiKey() == null || properties.apiKey().isBlank()) {
      return new AiKeyTestResponse(false, "missing_key", "Gemini API key ontbreekt.");
    }
    try {
      restClient.get()
          .uri(uriBuilder -> uriBuilder
              .path("/v1beta/models")
              .queryParam("key", properties.apiKey())
              .build())
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .body(JsonNode.class);
      return new AiKeyTestResponse(true, "ok", "API key is geldig.");
    } catch (RestClientResponseException ex) {
      return mapTestError(ex.getStatusCode().value(), ex.getResponseBodyAsString());
    } catch (Exception ex) {
      return new AiKeyTestResponse(false, "error", "Kon Gemini niet bereiken.");
    }
  }

  private String buildPrompt(String systemPrompt, String userPrompt) {
    return (systemPrompt == null ? "" : systemPrompt.trim())
        + "\n\n"
        + (userPrompt == null ? "" : userPrompt.trim());
  }

  private String resolveModel() {
    String configuredModel = appSettingsService.getAiModel();
    if (configuredModel != null && !configuredModel.isBlank()) {
      String normalized = configuredModel.trim().toLowerCase(Locale.ROOT);
      if (normalized.startsWith("gemini")) {
        return configuredModel.trim();
      }
    }
    if (properties.model() != null && !properties.model().isBlank()) {
      return properties.model().trim();
    }
    return "gemini-2.0-flash";
  }

  private String extractGeminiText(JsonNode response) {
    if (response == null) {
      return null;
    }
    JsonNode parts = response.path("candidates").path(0).path("content").path("parts");
    if (parts.isArray() && !parts.isEmpty()) {
      String text = parts.path(0).path("text").asText(null);
      if (text != null && !text.isBlank()) {
        return text;
      }
    }
    return null;
  }

  private void handleAiFailure(RestClientResponseException ex) {
    int status = ex.getStatusCode().value();
    String body = ex.getResponseBodyAsString();
    String message = (body == null ? "" : body).toLowerCase(Locale.ROOT);

    if (status == 429) {
      Duration cooldown = determine429Cooldown(message, body);
      appSettingsService.recordAiFailure(body, cooldown);
      log.warn("Gemini categorization paused for {}s due to 429", cooldown.toSeconds());
      return;
    }
    log.warn("Gemini categorization failed ({}): {}", status, body);
  }

  private void handleAiFailure(Exception ex) {
    String message = ex.getMessage() == null ? "" : ex.getMessage();
    String lower = message.toLowerCase(Locale.ROOT);
    if (lower.contains("insufficient_quota") || lower.contains("resource_exhausted") || lower.contains("quota")) {
      appSettingsService.recordAiFailure(message, DEFAULT_QUOTA_COOLDOWN);
      log.warn("Gemini categorization paused for {}s due to quota", DEFAULT_QUOTA_COOLDOWN.toSeconds());
      return;
    }
    if (lower.contains("rate limit") || lower.contains("too many requests") || lower.contains("rate_limit")) {
      appSettingsService.recordAiFailure(message, DEFAULT_RATE_LIMIT_COOLDOWN);
      log.warn("Gemini categorization paused for {}s due to rate limit", DEFAULT_RATE_LIMIT_COOLDOWN.toSeconds());
      return;
    }
    log.warn("Gemini categorization failed: {}", message);
  }

  private AiKeyTestResponse mapTestError(int statusCode, String body) {
    String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
    if (statusCode == 401 || lower.contains("invalid api key") || lower.contains("api_key_invalid")) {
      return new AiKeyTestResponse(false, "invalid_key", "Ongeldige Gemini API key.");
    }
    if (statusCode == 429 && (lower.contains("insufficient_quota") || lower.contains("resource_exhausted"))) {
      return new AiKeyTestResponse(false, "quota", "Gemini quota is opgebruikt.");
    }
    if (statusCode == 429) {
      return new AiKeyTestResponse(false, "rate_limit", "Te veel aanvragen naar Gemini.");
    }
    if (statusCode == 403) {
      return new AiKeyTestResponse(false, "forbidden", "Geen toegang tot Gemini.");
    }
    return new AiKeyTestResponse(false, "error", "Gemini fout (" + statusCode + ").");
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

  private void throttleRequests() {
    synchronized (requestLock) {
      long now = System.currentTimeMillis();
      long waitMs = nextAllowedRequestAtMs - now;
      if (waitMs > 0) {
        try {
          Thread.sleep(waitMs);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      nextAllowedRequestAtMs = System.currentTimeMillis() + MIN_REQUEST_SPACING.toMillis();
    }
  }

  private Duration determine429Cooldown(String lowerMessage, String rawBody) {
    if (containsDailyQuotaSignal(lowerMessage)) {
      return QUOTA_COOLDOWN;
    }
    Duration retryDelay = extractRetryDelay(rawBody);
    if (!retryDelay.isZero()) {
      Duration buffered = retryDelay.plusSeconds(5);
      return buffered.compareTo(DEFAULT_QUOTA_COOLDOWN) > 0 ? buffered : DEFAULT_QUOTA_COOLDOWN;
    }
    if (lowerMessage.contains("insufficient_quota")
        || lowerMessage.contains("resource_exhausted")
        || lowerMessage.contains("quota")) {
      return DEFAULT_QUOTA_COOLDOWN;
    }
    return DEFAULT_RATE_LIMIT_COOLDOWN;
  }

  private boolean containsDailyQuotaSignal(String lowerMessage) {
    return lowerMessage.contains("perday")
        || lowerMessage.contains("requestsperday")
        || lowerMessage.contains("inputtokenspermodelperday");
  }

  private Duration extractRetryDelay(String rawBody) {
    if (rawBody == null || rawBody.isBlank()) {
      return Duration.ZERO;
    }
    Matcher matcher = RETRY_DELAY_PATTERN.matcher(rawBody);
    if (!matcher.find()) {
      return Duration.ZERO;
    }
    try {
      long seconds = Long.parseLong(matcher.group(1));
      if (seconds <= 0) {
        return Duration.ZERO;
      }
      return Duration.ofSeconds(seconds);
    } catch (Exception ignored) {
      return Duration.ZERO;
    }
  }
}
