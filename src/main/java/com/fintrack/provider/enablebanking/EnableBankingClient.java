package com.fintrack.provider.enablebanking;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fintrack.config.EnableBankingProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class EnableBankingClient {
  private static final Logger log = LoggerFactory.getLogger(EnableBankingClient.class);
  private static final String ISSUER = "enablebanking.com";
  private static final String AUDIENCE = "api.enablebanking.com";
  private static final int TOKEN_TTL_SECONDS = 300;

  private final EnableBankingProperties properties;
  private final RestClient restClient;

  private volatile PrivateKey cachedSandboxKey;
  private volatile PrivateKey cachedProdKey;

  public EnableBankingClient(EnableBankingProperties properties) {
    this.properties = properties;
    String baseUrl = properties.baseUrl() == null ? "" : properties.baseUrl();
    this.restClient = RestClient.builder().baseUrl(baseUrl).build();
  }

  public AuthorizationResponse startAuthorization(String aspspName,
                                                  String aspspCountry,
                                                  String redirectUrl,
                                                  String state,
                                                  String psuType,
                                                  String language,
                                                  String validUntil) {
    Map<String, Object> aspsp = new LinkedHashMap<>();
    aspsp.put("name", aspspName);
    aspsp.put("country", aspspCountry);

    Map<String, Object> access = new LinkedHashMap<>();
    access.put("balances", true);
    access.put("transactions", true);
    if (validUntil != null && !validUntil.isBlank()) {
      access.put("valid_until", validUntil);
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("aspsp", aspsp);
    body.put("access", access);
    body.put("state", state);
    body.put("redirect_url", redirectUrl);
    if (psuType != null && !psuType.isBlank()) {
      body.put("psu_type", psuType);
    }
    if (language != null && !language.isBlank()) {
      body.put("language", language);
    }

    return restClient.post()
        .uri("/auth")
        .header(HttpHeaders.AUTHORIZATION, bearerToken())
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .body(AuthorizationResponse.class);
  }

  public JsonNode createSession(String code) {
    Map<String, String> body = Map.of("code", code);
    try {
      return restClient.post()
          .uri("/sessions")
          .header(HttpHeaders.AUTHORIZATION, bearerToken())
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(JsonNode.class);
    } catch (HttpClientErrorException.UnprocessableEntity ex) {
      String payload = ex.getResponseBodyAsString();
      if (payload != null && payload.contains("ALREADY_AUTHORIZED")) {
        log.warn("Enable Banking session already authorized for this code.");
        return null;
      }
      throw ex;
    }
  }

  public JsonNode getSession(String sessionId) {
    return restClient.get()
        .uri("/sessions/{sessionId}", sessionId)
        .header(HttpHeaders.AUTHORIZATION, bearerToken())
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .body(JsonNode.class);
  }

  public JsonNode listAspsps(String country, String psuType) {
    String uri = "/aspsps";
    uri = appendQueryParam(uri, "country", country);
    uri = appendQueryParam(uri, "psu_type", psuType);
    return restClient.get()
        .uri(uri)
        .header(HttpHeaders.AUTHORIZATION, bearerToken())
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .body(JsonNode.class);
  }

  public JsonNode getAccountDetails(String accountId) {
    return restClient.get()
        .uri("/accounts/{accountId}/details", accountId)
        .header(HttpHeaders.AUTHORIZATION, bearerToken())
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .body(JsonNode.class);
  }

  public JsonNode getBalances(String accountId) {
    return restClient.get()
        .uri("/accounts/{accountId}/balances", accountId)
        .header(HttpHeaders.AUTHORIZATION, bearerToken())
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .body(JsonNode.class);
  }

  public JsonNode getTransactions(String accountId, String continuationKey) {
    String uri = "/accounts/" + accountId + "/transactions";
    uri = appendQueryParam(uri, "continuation_key", continuationKey);
    return restClient.get()
        .uri(uri)
        .header(HttpHeaders.AUTHORIZATION, bearerToken())
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .body(JsonNode.class);
  }

  public String formatValidUntil(int consentDays) {
    Instant expiresAt = Instant.now().plusSeconds((long) consentDays * 86400L);
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME
        .withZone(ZoneOffset.UTC)
        .format(expiresAt);
  }

  private String bearerToken() {
    return "Bearer " + createJwt();
  }

  private String createJwt() {
    String appId = requireValue(activeAppId(), "Enable Banking app id");
    PrivateKey key = loadPrivateKey();
    Instant now = Instant.now();
    return Jwts.builder()
        .setIssuer(ISSUER)
        .setAudience(AUDIENCE)
        .setIssuedAt(java.util.Date.from(now))
        .setExpiration(java.util.Date.from(now.plusSeconds(TOKEN_TTL_SECONDS)))
        .setHeaderParam("typ", "JWT")
        .setHeaderParam("kid", appId)
        .signWith(key, SignatureAlgorithm.RS256)
        .compact();
  }

  private PrivateKey loadPrivateKey() {
    if (isProduction()) {
      if (cachedProdKey == null) {
        cachedProdKey = readPrivateKey(requireValue(properties.privateKeyProdPath(), "Enable Banking prod key path"));
      }
      return cachedProdKey;
    }
    if (cachedSandboxKey == null) {
      cachedSandboxKey = readPrivateKey(requireValue(properties.privateKeyPath(), "Enable Banking sandbox key path"));
    }
    return cachedSandboxKey;
  }

  private PrivateKey readPrivateKey(String path) {
    try {
      String pem = Files.readString(Path.of(path), StandardCharsets.UTF_8);
      if (pem.contains("BEGIN RSA PRIVATE KEY")) {
        throw new IllegalStateException("Enable Banking key is PKCS#1. Convert to PKCS#8 (openssl pkcs8 -topk8 -nocrypt).");
      }
      String clean = pem
          .replace("-----BEGIN PRIVATE KEY-----", "")
          .replace("-----END PRIVATE KEY-----", "")
          .replaceAll("\\s+", "");
      byte[] decoded = Base64.getDecoder().decode(clean);
      PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      return keyFactory.generatePrivate(keySpec);
    } catch (Exception ex) {
      log.error("Failed to load Enable Banking private key from {}", path, ex);
      throw new IllegalStateException("Unable to load Enable Banking private key: " + ex.getMessage(), ex);
    }
  }

  public boolean isProduction() {
    String env = firstNonBlank(properties.environment(), System.getenv("ENABLE_BANKING_ENV"), "sandbox");
    return "production".equalsIgnoreCase(env);
  }

  public String activeAppId() {
    return isProduction() ? properties.productionAppId() : properties.sandboxAppId();
  }

  private String appendQueryParam(String path, String key, String value) {
    if (value == null || value.isBlank()) {
      return path;
    }
    String separator = path.contains("?") ? "&" : "?";
    return path + separator + key + "=" + java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String requireValue(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing " + field);
    }
    return value;
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  public static List<Aspsp> toAspspList(JsonNode root) {
    List<Aspsp> results = new ArrayList<>();
    if (root == null) {
      return results;
    }
    JsonNode array = root.isArray() ? root : root.path("aspsps");
    if (array == null || !array.isArray()) {
      return results;
    }
    for (JsonNode node : array) {
      String name = text(node, "name");
      String country = text(node, "country");
      if (name == null || country == null) {
        continue;
      }
      String logo = text(node, "logo");
      String bic = text(node, "bic");
      List<String> psuTypes = new ArrayList<>();
      JsonNode psuArray = node.path("psu_types");
      if (psuArray.isArray()) {
        for (JsonNode p : psuArray) {
          if (p.isTextual()) {
            psuTypes.add(p.asText().toLowerCase(Locale.ROOT));
          }
        }
      }
      results.add(new Aspsp(name, country, bic, logo, psuTypes));
    }
    return results;
  }

  private static String text(JsonNode node, String path) {
    JsonNode current = node;
    for (String part : path.split("\\.")) {
      if (current == null) {
        return null;
      }
      current = current.path(part);
      if (current.isMissingNode() || current.isNull()) {
        return null;
      }
    }
    return current.isTextual() ? current.asText() : current.toString();
  }

  public record AuthorizationResponse(
      String url,
      @JsonProperty("authorization_id") String authorizationId,
      @JsonProperty("psu_id_hash") String psuIdHash
  ) {}

  public record Aspsp(String name, String country, String bic, String logo, List<String> psuTypes) {}
}
