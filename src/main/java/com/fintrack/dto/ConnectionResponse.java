package com.fintrack.dto;

import com.fintrack.model.ConnectionStatus;
import com.fintrack.model.ConnectionType;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ConnectionResponse {
  private UUID id;
  private String providerId;
  private String displayName;
  private ConnectionType type;
  private ConnectionStatus status;
  private boolean autoSyncEnabled;
  private Instant lastSyncedAt;
  private String errorMessage;
  private Instant createdAt;
}
