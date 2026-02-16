package com.fintrack.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.model.Connection;
import com.fintrack.provider.bitvavo.BitvavoClient;
import com.fintrack.repository.ConnectionRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BitvavoDebugService {
  private final ConnectionRepository connectionRepository;
  private final CryptoService cryptoService;
  private final ObjectMapper objectMapper;
  private final BitvavoClient bitvavoClient;

  public BitvavoDebugService(ConnectionRepository connectionRepository,
                             CryptoService cryptoService,
                             ObjectMapper objectMapper,
                             BitvavoClient bitvavoClient) {
    this.connectionRepository = connectionRepository;
    this.cryptoService = cryptoService;
    this.objectMapper = objectMapper;
    this.bitvavoClient = bitvavoClient;
  }

  public BitvavoClient.RawHistoryResponse getRawHistory(UUID userId,
                                                        UUID connectionId,
                                                        Integer page,
                                                        Integer maxItems,
                                                        String type,
                                                        Long fromDate,
                                                        Long toDate) {
    Connection connection = connectionRepository.findByIdAndUserId(connectionId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
    if (connection.getProviderId() == null || !"bitvavo".equalsIgnoreCase(connection.getProviderId())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Connection is not Bitvavo");
    }
    Map<String, String> config = loadConfig(connection);
    String apiKey = config.get("apiKey");
    String apiSecret = config.get("apiSecret");
    if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitvavo API key/secret ontbreekt");
    }
    return bitvavoClient.getAccountHistoryRaw(apiKey, apiSecret, page, maxItems, type, fromDate, toDate);
  }

  private Map<String, String> loadConfig(Connection connection) {
    if (connection.getEncryptedConfig() == null || connection.getEncryptedConfig().isBlank()) {
      return Map.of();
    }
    try {
      String json = cryptoService.decrypt(connection.getEncryptedConfig());
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kon Bitvavo config niet lezen");
    }
  }
}
