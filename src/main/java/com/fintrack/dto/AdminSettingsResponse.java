package com.fintrack.dto;

import java.time.Instant;

public class AdminSettingsResponse {
  private boolean syncEnabled;
  private long syncIntervalMs;
  private boolean aiEnabled;
  private String aiModel;
  private Instant updatedAt;

  public AdminSettingsResponse() {}

  public AdminSettingsResponse(boolean syncEnabled,
                               long syncIntervalMs,
                               boolean aiEnabled,
                               String aiModel,
                               Instant updatedAt) {
    this.syncEnabled = syncEnabled;
    this.syncIntervalMs = syncIntervalMs;
    this.aiEnabled = aiEnabled;
    this.aiModel = aiModel;
    this.updatedAt = updatedAt;
  }

  public boolean isSyncEnabled() {
    return syncEnabled;
  }

  public void setSyncEnabled(boolean syncEnabled) {
    this.syncEnabled = syncEnabled;
  }

  public long getSyncIntervalMs() {
    return syncIntervalMs;
  }

  public void setSyncIntervalMs(long syncIntervalMs) {
    this.syncIntervalMs = syncIntervalMs;
  }

  public boolean isAiEnabled() {
    return aiEnabled;
  }

  public void setAiEnabled(boolean aiEnabled) {
    this.aiEnabled = aiEnabled;
  }

  public String getAiModel() {
    return aiModel;
  }

  public void setAiModel(String aiModel) {
    this.aiModel = aiModel;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
