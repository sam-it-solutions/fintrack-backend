package com.fintrack.dto;

import java.time.Instant;

public class AdminSettingsResponse {
  private boolean syncEnabled;
  private long syncIntervalMs;
  private long cryptoSyncIntervalMs;
  private boolean aiEnabled;
  private String aiModel;
  private java.time.Instant aiDisabledUntil;
  private String aiLastError;
  private java.time.Instant aiLastErrorAt;
  private Instant updatedAt;

  public AdminSettingsResponse() {}

  public AdminSettingsResponse(boolean syncEnabled,
                               long syncIntervalMs,
                               long cryptoSyncIntervalMs,
                               boolean aiEnabled,
                               String aiModel,
                               java.time.Instant aiDisabledUntil,
                               String aiLastError,
                               java.time.Instant aiLastErrorAt,
                               Instant updatedAt) {
    this.syncEnabled = syncEnabled;
    this.syncIntervalMs = syncIntervalMs;
    this.cryptoSyncIntervalMs = cryptoSyncIntervalMs;
    this.aiEnabled = aiEnabled;
    this.aiModel = aiModel;
    this.aiDisabledUntil = aiDisabledUntil;
    this.aiLastError = aiLastError;
    this.aiLastErrorAt = aiLastErrorAt;
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

  public long getCryptoSyncIntervalMs() {
    return cryptoSyncIntervalMs;
  }

  public void setCryptoSyncIntervalMs(long cryptoSyncIntervalMs) {
    this.cryptoSyncIntervalMs = cryptoSyncIntervalMs;
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

  public java.time.Instant getAiDisabledUntil() {
    return aiDisabledUntil;
  }

  public void setAiDisabledUntil(java.time.Instant aiDisabledUntil) {
    this.aiDisabledUntil = aiDisabledUntil;
  }

  public String getAiLastError() {
    return aiLastError;
  }

  public void setAiLastError(String aiLastError) {
    this.aiLastError = aiLastError;
  }

  public java.time.Instant getAiLastErrorAt() {
    return aiLastErrorAt;
  }

  public void setAiLastErrorAt(java.time.Instant aiLastErrorAt) {
    this.aiLastErrorAt = aiLastErrorAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
