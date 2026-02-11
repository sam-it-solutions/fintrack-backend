package com.fintrack.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.dto.ConnectResponse;
import com.fintrack.dto.ConnectionResponse;
import com.fintrack.dto.CreateConnectionRequest;
import com.fintrack.dto.ProviderResponse;
import com.fintrack.dto.UpdateConnectionRequest;
import com.fintrack.model.Connection;
import com.fintrack.model.ConnectionStatus;
import com.fintrack.provider.ConnectResult;
import com.fintrack.provider.ConnectionProvider;
import com.fintrack.provider.ProviderRegistry;
import com.fintrack.repository.ConnectionRepository;
import com.fintrack.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConnectionService {
  private final ConnectionRepository connectionRepository;
  private final UserRepository userRepository;
  private final ProviderRegistry providerRegistry;
  private final CryptoService cryptoService;
  private final ObjectMapper objectMapper;
  private final NotificationService notificationService;

  public ConnectionService(ConnectionRepository connectionRepository,
                           UserRepository userRepository,
                           ProviderRegistry providerRegistry,
                           CryptoService cryptoService,
                           ObjectMapper objectMapper,
                           NotificationService notificationService) {
    this.connectionRepository = connectionRepository;
    this.userRepository = userRepository;
    this.providerRegistry = providerRegistry;
    this.cryptoService = cryptoService;
    this.objectMapper = objectMapper;
    this.notificationService = notificationService;
  }

  public List<ProviderResponse> listProviders() {
    return providerRegistry.list().stream().map(ConnectionProvider::getMetadata).toList();
  }

  public ConnectionResponse createConnection(UUID userId, CreateConnectionRequest request) {
    ConnectionProvider provider = providerRegistry.require(request.getProviderId());
    Connection connection = new Connection();
    connection.setUser(userRepository.findById(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));
    connection.setProviderId(provider.getProviderId());
    connection.setType(provider.getMetadata().getType());
    connection.setDisplayName(request.getDisplayName() == null || request.getDisplayName().isBlank()
        ? provider.getMetadata().getName()
        : request.getDisplayName());
    connection.setStatus(provider.getMetadata().isRequiresAuth() ? ConnectionStatus.PENDING : ConnectionStatus.ACTIVE);
    connection.setAutoSyncEnabled(true);
    connection.setEncryptedConfig(storeConfig(request.getConfig()));
    Connection saved = connectionRepository.save(connection);
    return toResponse(saved);
  }

  public List<ConnectionResponse> listConnections(UUID userId) {
    return connectionRepository.findByUserId(userId).stream()
        .filter(connection -> connection.getStatus() != ConnectionStatus.DISABLED)
        .map(this::toResponse)
        .toList();
  }

  public ConnectionResponse updateConnection(UUID userId, UUID connectionId, UpdateConnectionRequest request) {
    Connection connection = connectionRepository.findByIdAndUserId(connectionId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
    if (request.getDisplayName() != null && !request.getDisplayName().isBlank()) {
      connection.setDisplayName(request.getDisplayName());
    }
    if (request.getAutoSyncEnabled() != null) {
      connection.setAutoSyncEnabled(request.getAutoSyncEnabled());
    }
    return toResponse(connectionRepository.save(connection));
  }

  public void disableConnection(UUID userId, UUID connectionId) {
    Connection connection = connectionRepository.findByIdAndUserId(connectionId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
    connection.setStatus(ConnectionStatus.DISABLED);
    connection.setAutoSyncEnabled(false);
    connectionRepository.save(connection);
  }

  public ConnectResponse initiateConnection(UUID userId, UUID connectionId) {
    Connection connection = connectionRepository.findByIdAndUserId(connectionId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
    ConnectionProvider provider = providerRegistry.require(connection.getProviderId());
    Map<String, String> config = loadConfig(connection);
    ConnectResult result = provider.initiate(connection, config);
    connection.setExternalId(result.externalId());
    connection.setStatus(result.status());
    connectionRepository.save(connection);
    return new ConnectResponse(result.redirectUrl());
  }

  public void syncConnection(UUID userId, UUID connectionId) {
    Connection connection = connectionRepository.findByIdAndUserId(connectionId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
    syncConnection(connection);
  }

  public void syncConnectionById(UUID connectionId) {
    Connection connection = connectionRepository.findById(connectionId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
    syncConnection(connection);
  }

  public void syncConnection(Connection connection) {
    if (connection.getStatus() == ConnectionStatus.DISABLED) {
      return;
    }
    if (shouldBackoffRateLimit(connection)) {
      Instant retryAt = nextAllowedSync(connection);
      if (retryAt != null) {
        connection.setStatus(ConnectionStatus.ERROR);
        connection.setErrorMessage("Rate limit actief. Probeer opnieuw na " + retryAt.toString());
        connectionRepository.save(connection);
      }
      return;
    }
    ConnectionStatus previous = connection.getStatus();
    ConnectionProvider provider = providerRegistry.require(connection.getProviderId());
    Map<String, String> config = loadConfig(connection);
    try {
      provider.sync(connection, config);
      connection.setStatus(ConnectionStatus.ACTIVE);
      connection.setErrorMessage(null);
      connection.setLastSyncedAt(java.time.Instant.now());
    } catch (Exception ex) {
      connection.setStatus(ConnectionStatus.ERROR);
      if (isRateLimitError(ex.getMessage())) {
        Instant retryAt = nextAllowedSync(connection);
        if (retryAt == null) {
          retryAt = Instant.now().plus(24, ChronoUnit.HOURS);
        }
        connection.setErrorMessage("Rate limit actief. Probeer opnieuw na " + retryAt.toString());
      } else {
        connection.setErrorMessage(ex.getMessage());
      }
      if (previous != ConnectionStatus.ERROR) {
        notifyError(connection, ex.getMessage());
      }
    }
    connectionRepository.save(connection);
  }

  private boolean shouldBackoffRateLimit(Connection connection) {
    if (!isRateLimitError(connection.getErrorMessage())) {
      return false;
    }
    Instant retryAt = nextAllowedSync(connection);
    if (retryAt == null) {
      return false;
    }
    return Instant.now().isBefore(retryAt);
  }

  private Instant nextAllowedSync(Connection connection) {
    Instant lastSynced = connection.getLastSyncedAt();
    if (lastSynced != null) {
      return lastSynced.plus(24, ChronoUnit.HOURS);
    }
    Instant updated = connection.getUpdatedAt();
    if (updated != null) {
      return updated.plus(24, ChronoUnit.HOURS);
    }
    return null;
  }

  private boolean isRateLimitError(String message) {
    if (message == null || message.isBlank()) {
      return false;
    }
    String lowered = message.toLowerCase();
    return lowered.contains("aspsp_rate_limit_exceeded")
        || lowered.contains("rate limit")
        || lowered.contains("too many requests")
        || lowered.contains("429")
        || lowered.contains("ratelimitexception");
  }

  private void notifyError(Connection connection, String message) {
    if (connection.getUser() == null || connection.getUser().getEmail() == null) {
      return;
    }
    String subject = "Sync probleem bij " + connection.getDisplayName();
    String body = "Er ging iets mis bij het synchroniseren van " + connection.getDisplayName() + ".\n\n"
        + "Foutmelding: " + message + "\n\n"
        + "Controleer je koppeling in Fintrack.";
    notificationService.send(connection.getUser().getEmail(), subject, body);
  }

  private String storeConfig(Map<String, String> config) {
    if (config == null || config.isEmpty()) {
      return null;
    }
    try {
      return cryptoService.encrypt(objectMapper.writeValueAsString(config));
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid config payload");
    }
  }

  private Map<String, String> loadConfig(Connection connection) {
    if (connection.getEncryptedConfig() == null) {
      return Map.of();
    }
    try {
      String json = cryptoService.decrypt(connection.getEncryptedConfig());
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid config payload");
    }
  }

  private ConnectionResponse toResponse(Connection connection) {
    return new ConnectionResponse(
        connection.getId(),
        connection.getProviderId(),
        connection.getDisplayName(),
        connection.getType(),
        connection.getStatus(),
        connection.isAutoSyncEnabled(),
        connection.getLastSyncedAt(),
        connection.getErrorMessage(),
        connection.getCreatedAt());
  }
}
