package com.fintrack.service;

import com.fintrack.model.Connection;
import com.fintrack.repository.ConnectionRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SyncProgressService {
  private final ConnectionRepository connectionRepository;

  public SyncProgressService(ConnectionRepository connectionRepository) {
    this.connectionRepository = connectionRepository;
  }

  public void update(Connection connection, String stage, Integer progress) {
    if (connection == null) {
      return;
    }
    boolean changed = !Objects.equals(connection.getSyncStage(), stage)
        || !Objects.equals(connection.getSyncProgress(), progress);
    if (!changed) {
      return;
    }
    connection.setSyncStage(stage);
    connection.setSyncProgress(progress);
    connectionRepository.save(connection);
  }

  public void update(UUID connectionId, String stage, Integer progress) {
    if (connectionId == null) {
      return;
    }
    connectionRepository.findById(connectionId)
        .ifPresent(connection -> update(connection, stage, progress));
  }
}
