package com.fintrack.provider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ProviderRegistry {
  private final Map<String, ConnectionProvider> providers;

  public ProviderRegistry(List<ConnectionProvider> providers) {
    this.providers = providers.stream()
        .collect(Collectors.toMap(ConnectionProvider::getProviderId, Function.identity()));
  }

  public ConnectionProvider require(String providerId) {
    ConnectionProvider provider = providers.get(providerId);
    if (provider == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown provider: " + providerId);
    }
    return provider;
  }

  public List<ConnectionProvider> list() {
    return providers.values().stream().toList();
  }
}
