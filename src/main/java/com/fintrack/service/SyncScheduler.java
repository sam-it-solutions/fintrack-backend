package com.fintrack.service;

import com.fintrack.model.ConnectionStatus;
import com.fintrack.model.ConnectionType;
import com.fintrack.model.SyncStatus;
import com.fintrack.repository.ConnectionRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SyncScheduler {
  private final ConnectionRepository connectionRepository;
  private final ConnectionService connectionService;
  private final AppSettingsService appSettingsService;

  public SyncScheduler(ConnectionRepository connectionRepository,
                       ConnectionService connectionService,
                       AppSettingsService appSettingsService) {
    this.connectionRepository = connectionRepository;
    this.connectionService = connectionService;
    this.appSettingsService = appSettingsService;
  }

  @Scheduled(fixedDelayString = "${fintrack.sync.poll-ms:60000}")
  public void run() {
    if (!appSettingsService.isSyncEnabled()) {
      return;
    }
    Instant now = Instant.now();
    long intervalMs = appSettingsService.getSyncIntervalMs();
    long cryptoIntervalMs = appSettingsService.getCryptoSyncIntervalMs();
    connectionRepository.findByAutoSyncEnabledTrueAndStatus(ConnectionStatus.ACTIVE)
        .stream()
        .filter(connection -> shouldSync(connection, now, intervalMs, cryptoIntervalMs))
        .forEach(connectionService::syncConnection);
  }

  private boolean shouldSync(com.fintrack.model.Connection connection,
                             Instant now,
                             long intervalMs,
                             long cryptoIntervalMs) {
    if (connection.getSyncStatus() == SyncStatus.RUNNING) {
      return false;
    }
    long effectiveInterval = connection.getType() == ConnectionType.CRYPTO
        ? cryptoIntervalMs
        : intervalMs;
    if (effectiveInterval <= 0) {
      return true;
    }
    Instant lastSync = connection.getLastSyncCompletedAt();
    if (lastSync == null) {
      lastSync = connection.getLastSyncedAt();
    }
    if (lastSync == null) {
      return true;
    }
    return Duration.between(lastSync, now).toMillis() >= effectiveInterval;
  }
}
