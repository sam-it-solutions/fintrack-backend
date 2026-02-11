package com.fintrack.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.model.Connection;
import com.fintrack.model.ConnectionStatus;
import com.fintrack.provider.tink.TinkClient;
import com.fintrack.repository.ConnectionRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TinkService {
  private final ConnectionRepository connectionRepository;
  private final CryptoService cryptoService;
  private final ObjectMapper objectMapper;
  private final TinkClient tinkClient;

  public TinkService(ConnectionRepository connectionRepository,
                     CryptoService cryptoService,
                     ObjectMapper objectMapper,
                     TinkClient tinkClient) {
    this.connectionRepository = connectionRepository;
    this.cryptoService = cryptoService;
    this.objectMapper = objectMapper;
    this.tinkClient = tinkClient;
  }

  public void handleCallback(UUID connectionId, String code) {
    Connection connection = connectionRepository.findById(connectionId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));

    TinkClient.TokenResponse token = tinkClient.exchangeCode(code);
    if (token == null || token.accessToken() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tink token exchange failed");
    }

    Map<String, String> config = loadConfig(connection);
    config.put("accessToken", token.accessToken());
    if (token.refreshToken() != null) {
      config.put("refreshToken", token.refreshToken());
    }
    if (token.tokenType() != null) {
      config.put("tokenType", token.tokenType());
    }
    if (token.expiresIn() != null) {
      config.put("expiresIn", token.expiresIn().toString());
    }
    config.put("tokenObtainedAt", Instant.now().toString());

    connection.setEncryptedConfig(storeConfig(config));
    connection.setStatus(ConnectionStatus.ACTIVE);
    connection.setErrorMessage(null);
    connectionRepository.save(connection);
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
}
