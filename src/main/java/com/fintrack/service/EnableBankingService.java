package com.fintrack.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.dto.EnableBankingAspspResponse;
import com.fintrack.model.Connection;
import com.fintrack.model.ConnectionStatus;
import com.fintrack.provider.enablebanking.EnableBankingClient;
import com.fintrack.repository.ConnectionRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EnableBankingService {
  private final ConnectionRepository connectionRepository;
  private final CryptoService cryptoService;
  private final ObjectMapper objectMapper;
  private final EnableBankingClient client;

  public EnableBankingService(ConnectionRepository connectionRepository,
                              CryptoService cryptoService,
                              ObjectMapper objectMapper,
                              EnableBankingClient client) {
    this.connectionRepository = connectionRepository;
    this.cryptoService = cryptoService;
    this.objectMapper = objectMapper;
    this.client = client;
  }

  public void handleCallback(UUID connectionId, String code) {
    Connection connection = connectionRepository.findById(connectionId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));

    Map<String, String> config = loadConfig(connection);
    if (config.get("sessionId") != null && !config.get("sessionId").isBlank()) {
      connection.setStatus(ConnectionStatus.ACTIVE);
      connection.setErrorMessage(null);
      connectionRepository.save(connection);
      return;
    }

    var session = client.createSession(code);
    if (session == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enable Banking session creation failed");
    }
    String sessionId = firstNonBlank(text(session, "session_id"), text(session, "sessionId"));
    if (sessionId == null || sessionId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enable Banking session creation failed");
    }

    config.put("sessionId", sessionId);
    config.put("sessionObtainedAt", Instant.now().toString());
    List<String> accountIds = extractAccountIds(session);
    if (!accountIds.isEmpty()) {
      config.put("accountIds", String.join(",", accountIds));
    }
    String psuIdHash = firstNonBlank(text(session, "psu_id_hash"), text(session, "psuIdHash"));
    String psuIdHashAlg = firstNonBlank(text(session, "psu_id_hash_alg"), text(session, "psuIdHashAlg"));
    if (psuIdHash != null) {
      config.put("psuIdHash", psuIdHash);
    }
    if (psuIdHashAlg != null) {
      config.put("psuIdHashAlg", psuIdHashAlg);
    }

    connection.setEncryptedConfig(storeConfig(config));
    connection.setStatus(ConnectionStatus.ACTIVE);
    connection.setErrorMessage(null);
    connectionRepository.save(connection);
  }

  public List<EnableBankingAspspResponse> listAspsps(String country, String psuType) {
    return EnableBankingClient.toAspspList(client.listAspsps(country, psuType))
        .stream()
        .map(aspsp -> new EnableBankingAspspResponse(
            aspsp.name(),
            aspsp.country(),
            aspsp.bic(),
            aspsp.logo(),
            aspsp.psuTypes()))
        .toList();
  }

  private Map<String, String> loadConfig(Connection connection) {
    if (connection.getEncryptedConfig() == null) {
      return new HashMap<>();
    }
    try {
      String json = cryptoService.decrypt(connection.getEncryptedConfig());
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid config payload");
    }
  }

  private String storeConfig(Map<String, String> config) {
    try {
      return cryptoService.encrypt(objectMapper.writeValueAsString(config));
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid config payload");
    }
  }

  private static String text(com.fasterxml.jackson.databind.JsonNode node, String path) {
    com.fasterxml.jackson.databind.JsonNode current = node;
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

  private static List<String> extractAccountIds(com.fasterxml.jackson.databind.JsonNode sessionResponse) {
    var ids = new java.util.LinkedHashSet<String>();
    if (sessionResponse == null) {
      return List.of();
    }
    collectAccountIds(ids, sessionResponse.path("accounts"));
    collectAccountIds(ids, sessionResponse.path("accounts_data"));
    collectAccountIds(ids, sessionResponse.path("account_ids"));
    collectAccountIds(ids, sessionResponse.path("accountIds"));
    var accountIdNode = sessionResponse.path("account_id");
    String singleId = normalizeAccountId(accountIdNode);
    if (singleId != null) {
      ids.add(singleId);
    }
    return new java.util.ArrayList<>(ids);
  }

  private static String normalizeAccountId(com.fasterxml.jackson.databind.JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    if (node.isTextual() || node.isNumber()) {
      return node.asText();
    }
    if (node.isObject()) {
      String id = firstNonBlank(
          text(node, "uid"),
          text(node, "resource_id"),
          text(node, "resourceId"),
          text(node, "account_id.uid"),
          text(node, "account_id.id"),
          text(node, "id"),
          text(node, "account_id"),
          text(node, "accountId"),
          text(node, "iban"),
          text(node, "bban"),
          text(node, "account_number"));
      return id == null || id.isBlank() ? null : id;
    }
    return null;
  }

  private static void collectAccountIds(java.util.LinkedHashSet<String> ids,
                                        com.fasterxml.jackson.databind.JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return;
    }
    if (node.isArray()) {
      for (var item : node) {
        String id = normalizeAccountId(item);
        if (id == null) {
          id = normalizeAccountId(item.path("account_id"));
        }
        if (id != null) {
          ids.add(id);
        }
      }
      return;
    }
    if (node.isObject()) {
      com.fasterxml.jackson.databind.JsonNode nested = extractArray(node, "accounts", "items", "data", "results");
      if (nested != null) {
        collectAccountIds(ids, nested);
        return;
      }
      String id = normalizeAccountId(node);
      if (id != null) {
        ids.add(id);
      }
      return;
    }
    String id = normalizeAccountId(node);
    if (id != null) {
      ids.add(id);
    }
  }

  private static com.fasterxml.jackson.databind.JsonNode extractArray(
      com.fasterxml.jackson.databind.JsonNode root,
      String... candidates) {
    if (root == null) {
      return null;
    }
    if (root.isArray()) {
      return root;
    }
    for (String key : candidates) {
      com.fasterxml.jackson.databind.JsonNode node = root.path(key);
      if (node != null && node.isArray()) {
        return node;
      }
    }
    return null;
  }
}
