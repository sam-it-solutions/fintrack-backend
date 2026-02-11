package com.fintrack.provider.tink;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fintrack.config.TinkProperties;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;

@Component
public class TinkClient {
  private static final Logger log = LoggerFactory.getLogger(TinkClient.class);

  private final TinkProperties properties;
  private final RestClient restClient;

  public TinkClient(TinkProperties properties) {
    this.properties = properties;
    String baseUrl = properties.baseUrl() == null ? "" : properties.baseUrl();
    this.restClient = RestClient.builder().baseUrl(baseUrl).build();
  }

  public TokenResponse exchangeCode(String code) {
    requireConfigured("clientId", properties.clientId());
    requireConfigured("clientSecret", properties.clientSecret());
    requireConfigured("redirectUrl", properties.redirectUrl());
    requireConfigured("tokenPath", properties.tokenPath());

    MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
    body.add("grant_type", "authorization_code");
    body.add("code", code);
    body.add("client_id", properties.clientId());
    body.add("client_secret", properties.clientSecret());
    body.add("redirect_uri", properties.redirectUrl());

    return restClient.post()
        .uri(resolveUri(properties.tokenPath()))
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(body)
        .retrieve()
        .body(TokenResponse.class);
  }

  public JsonNode listAccounts(String accessToken) {
    return listAccounts(accessToken, null);
  }

  public JsonNode listAccounts(String accessToken, String pageToken) {
    requireConfigured("accountsPath", properties.accountsPath());
    String uri = resolveUri(properties.accountsPath());
    uri = appendQueryParam(uri, "pageSize", "100");
    uri = appendQueryParam(uri, "pageToken", pageToken);
    return getJson(uri, accessToken);
  }

  public JsonNode listTransactions(String accessToken, String accountId) {
    return listTransactions(accessToken, accountId, null);
  }

  public JsonNode listTransactions(String accessToken, String accountId, String pageToken) {
    requireConfigured("transactionsPath", properties.transactionsPath());
    String path = properties.transactionsPath();
    if (accountId != null && path != null) {
      path = path.replace("{accountId}", accountId).replace("{account_id}", accountId);
      if (!path.contains(accountId) && !path.contains("accountIdIn")) {
        path = appendQueryParam(path, "accountIdIn", accountId);
      }
    }
    path = appendQueryParam(path, "pageSize", "100");
    path = appendQueryParam(path, "pageToken", pageToken);
    path = appendQueryParam(path, "statusIn", "BOOKED");
    path = appendQueryParam(path, "statusIn", "PENDING");
    return getJson(resolveUri(path), accessToken);
  }

  private JsonNode getJson(String uri, String accessToken) {
    Set<String> contentTypes = new LinkedHashSet<>();
    if (properties.dataContentType() != null && !properties.dataContentType().isBlank()) {
      contentTypes.add(properties.dataContentType());
    }
    contentTypes.add(MediaType.APPLICATION_JSON_VALUE);
    contentTypes.add(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
    contentTypes.add(null);

    HttpClientErrorException last415 = null;
    for (String contentType : contentTypes) {
      try {
        RestClient.RequestHeadersSpec<?> spec = restClient.get()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .accept(MediaType.APPLICATION_JSON);
        if (contentType != null && !contentType.isBlank()) {
          spec = spec.header(HttpHeaders.CONTENT_TYPE, contentType);
        }
        if (isDebugLogEnabled()) {
          log.info("Tink GET {} (Content-Type: {})", uri, contentType == null ? "<none>" : contentType);
        }
        JsonNode body = spec.retrieve().body(JsonNode.class);
        if (isDebugLogEnabled()) {
          log.info("Tink response {}: {}", uri, truncate(body == null ? "null" : body.toString(), 2000));
        }
        return body;
      } catch (HttpClientErrorException ex) {
        if (ex.getStatusCode() == HttpStatus.UNSUPPORTED_MEDIA_TYPE) {
          if (isDebugLogEnabled()) {
            log.warn("Tink 415 for {} (Content-Type: {}): {}", uri, contentType == null ? "<none>" : contentType,
                truncate(ex.getResponseBodyAsString(), 2000));
          }
          last415 = ex;
          continue;
        }
        throw ex;
      }
    }
    if (last415 != null) {
      throw last415;
    }
    return null;
  }

  private String appendQueryParam(String path, String key, String value) {
    if (path == null) {
      return null;
    }
    if (value == null || value.isBlank()) {
      return path;
    }
    String separator = path.contains("?") ? "&" : "?";
    return path + separator + key + "=" + java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  private boolean isDebugLogEnabled() {
    return Boolean.TRUE.equals(properties.debugLogResponses());
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength) + "...";
  }

  private String resolveUri(String path) {
    if (path == null) {
      return null;
    }
    if (path.startsWith("http://") || path.startsWith("https://")) {
      return path;
    }
    return path;
  }

  private void requireConfigured(String name, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing Tink configuration: " + name);
    }
  }

  public record TokenResponse(
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("refresh_token") String refreshToken,
      @JsonProperty("expires_in") Long expiresIn,
      @JsonProperty("token_type") String tokenType,
      @JsonProperty("scope") String scope
  ) {}
}
