package com.fintrack.dto;

public class AdminSettingsRequest {
  private Boolean syncEnabled;
  private Long syncIntervalMs;
  private Long cryptoSyncIntervalMs;
  private Boolean aiEnabled;
  private String aiModel;

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
}
