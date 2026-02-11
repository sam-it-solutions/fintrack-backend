package com.fintrack.repository;

import com.fintrack.model.Connection;
import com.fintrack.model.ConnectionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectionRepository extends JpaRepository<Connection, UUID> {
  List<Connection> findByUserId(UUID userId);
  Optional<Connection> findByIdAndUserId(UUID id, UUID userId);
  List<Connection> findByAutoSyncEnabledTrueAndStatus(ConnectionStatus status);
}
