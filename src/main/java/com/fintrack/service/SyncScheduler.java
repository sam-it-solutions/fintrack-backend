package com.fintrack.service;

import com.fintrack.model.ConnectionStatus;
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
  private Instant lastRunAt;

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
    long intervalMs = appSettingsService.getSyncIntervalMs();
    Instant now = Instant.now();
    if (lastRunAt != null && Duration.between(lastRunAt, now).toMillis() < intervalMs) {
      return;
    }
    lastRunAt = now;
    connectionRepository.findByAutoSyncEnabledTrueAndStatus(ConnectionStatus.ACTIVE)
        .forEach(connectionService::syncConnection);
  }
}
