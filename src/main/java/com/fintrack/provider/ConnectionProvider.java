package com.fintrack.provider;

import com.fintrack.dto.ProviderResponse;
import com.fintrack.model.Connection;
import java.util.Map;

public interface ConnectionProvider {
  String getProviderId();
  ProviderResponse getMetadata();
  ConnectResult initiate(Connection connection, Map<String, String> config);
  SyncResult sync(Connection connection, Map<String, String> config);
}
