package com.fintrack.service;

import com.fintrack.config.SyncProperties;
import com.fintrack.model.ConnectionStatus;
import com.fintrack.repository.ConnectionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SyncScheduler {
  private final SyncProperties syncProperties;
  private final ConnectionRepository connectionRepository;
  private final ConnectionService connectionService;

  public SyncScheduler(SyncProperties syncProperties,
                       ConnectionRepository connectionRepository,
                       ConnectionService connectionService) {
    this.syncProperties = syncProperties;
    this.connectionRepository = connectionRepository;
    this.connectionService = connectionService;
  }

  @Scheduled(fixedDelayString = "${fintrack.sync.interval-ms:3600000}")
  public void run() {
    if (!syncProperties.enabled()) {
      return;
    }
    connectionRepository.findByAutoSyncEnabledTrueAndStatus(ConnectionStatus.ACTIVE)
        .forEach(connectionService::syncConnection);
  }
}
