package com.fintrack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_settings")
public class AppSettings {
  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "sync_enabled")
  private Boolean syncEnabled;

  @Column(name = "sync_interval_ms")
  private Long syncIntervalMs;

  @Column(name = "crypto_sync_interval_ms")
  private Long cryptoSyncIntervalMs;

  @Column(name = "ai_enabled")
  private Boolean aiEnabled;

  @Column(name = "ai_model")
  private String aiModel;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @PrePersist
  public void prePersist() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    updatedAt = now;
  }

  @PreUpdate
  public void preUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public Boolean getSyncEnabled() {
    return syncEnabled;
  }

  public void setSyncEnabled(Boolean syncEnabled) {
    this.syncEnabled = syncEnabled;
  }

  public Long getSyncIntervalMs() {
    return syncIntervalMs;
  }

  public void setSyncIntervalMs(Long syncIntervalMs) {
    this.syncIntervalMs = syncIntervalMs;
  }

  public Long getCryptoSyncIntervalMs() {
    return cryptoSyncIntervalMs;
  }

  public void setCryptoSyncIntervalMs(Long cryptoSyncIntervalMs) {
    this.cryptoSyncIntervalMs = cryptoSyncIntervalMs;
  }

  public Boolean getAiEnabled() {
    return aiEnabled;
  }

  public void setAiEnabled(Boolean aiEnabled) {
    this.aiEnabled = aiEnabled;
  }

  public String getAiModel() {
    return aiModel;
  }

  public void setAiModel(String aiModel) {
    this.aiModel = aiModel;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
