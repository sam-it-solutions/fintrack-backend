package com.fintrack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "connections")
@Getter
@Setter
public class Connection {
  @Id
  private UUID id;

  @ManyToOne(optional = false)
  @JoinColumn(name = "user_id")
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ConnectionType type;

  @Column(name = "provider_id", nullable = false)
  private String providerId;

  @Column(nullable = false)
  private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ConnectionStatus status;

  @Column(columnDefinition = "TEXT")
  private String encryptedConfig;

  @Column
  private String externalId;

  @Column(nullable = false)
  private boolean autoSyncEnabled = true;

  @Column
  private Instant lastSyncedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "sync_status")
  private SyncStatus syncStatus = SyncStatus.IDLE;

  @Column(name = "sync_stage")
  private String syncStage;

  @Column(name = "sync_progress")
  private Integer syncProgress;

  @Column
  private Instant lastSyncStartedAt;

  @Column
  private Instant lastSyncCompletedAt;

  @Column(columnDefinition = "TEXT")
  private String lastSyncError;

  @Column
  private Instant createdAt;

  @Column
  private Instant updatedAt;

  @Column(columnDefinition = "TEXT")
  private String errorMessage;

  @PrePersist
  void prePersist() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }
}
