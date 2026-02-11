package com.fintrack.service;

import com.fintrack.config.OpenAiProperties;
import com.fintrack.config.SyncProperties;
import com.fintrack.dto.AdminSettingsRequest;
import com.fintrack.dto.AdminSettingsResponse;
import com.fintrack.model.AppSettings;
import com.fintrack.repository.AppSettingsRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AppSettingsService {
  private static final long MIN_SYNC_INTERVAL_MS = 5 * 60 * 1000L;
  private static final long MAX_SYNC_INTERVAL_MS = 24 * 60 * 60 * 1000L;

  private final AppSettingsRepository repository;
  private final SyncProperties syncProperties;
  private final OpenAiProperties openAiProperties;

  public AppSettingsService(AppSettingsRepository repository,
                            SyncProperties syncProperties,
                            OpenAiProperties openAiProperties) {
    this.repository = repository;
    this.syncProperties = syncProperties;
    this.openAiProperties = openAiProperties;
  }

  public AdminSettingsResponse getSettings() {
    AppSettings settings = getOrCreate();
    return toResponse(settings);
  }

  public AdminSettingsResponse updateSettings(AdminSettingsRequest request) {
    AppSettings settings = getOrCreate();
    if (request.getSyncEnabled() != null) {
      settings.setSyncEnabled(request.getSyncEnabled());
    }
    if (request.getSyncIntervalMs() != null) {
      long normalized = Math.max(MIN_SYNC_INTERVAL_MS, Math.min(MAX_SYNC_INTERVAL_MS, request.getSyncIntervalMs()));
      settings.setSyncIntervalMs(normalized);
    }
    if (request.getAiEnabled() != null) {
      settings.setAiEnabled(request.getAiEnabled());
    }
    if (request.getAiModel() != null) {
      String model = request.getAiModel().trim();
      settings.setAiModel(model.isBlank() ? null : model);
    }
    repository.save(settings);
    return toResponse(settings);
  }

  public boolean isSyncEnabled() {
    AppSettings settings = getOrCreate();
    Boolean enabled = settings.getSyncEnabled();
    return enabled != null ? enabled : syncProperties.enabled();
  }

  public long getSyncIntervalMs() {
    AppSettings settings = getOrCreate();
    Long interval = settings.getSyncIntervalMs();
    return interval != null ? interval : syncProperties.intervalMs();
  }

  public boolean isAiEnabled() {
    AppSettings settings = getOrCreate();
    Boolean enabled = settings.getAiEnabled();
    return enabled != null ? enabled : Boolean.TRUE.equals(openAiProperties.enabled());
  }

  public String getAiModel() {
    AppSettings settings = getOrCreate();
    String model = settings.getAiModel();
    if (model != null && !model.isBlank()) {
      return model;
    }
    return openAiProperties.model();
  }

  private AppSettings getOrCreate() {
    Optional<AppSettings> existing = repository.findFirstByOrderByCreatedAtAsc();
    if (existing.isPresent()) {
      return existing.get();
    }
    AppSettings created = new AppSettings();
    created.setSyncEnabled(syncProperties.enabled());
    created.setSyncIntervalMs(syncProperties.intervalMs());
    created.setAiEnabled(openAiProperties.enabled());
    created.setAiModel(openAiProperties.model());
    return repository.save(created);
  }

  private AdminSettingsResponse toResponse(AppSettings settings) {
    boolean syncEnabled = settings.getSyncEnabled() != null
        ? settings.getSyncEnabled()
        : syncProperties.enabled();
    long syncIntervalMs = settings.getSyncIntervalMs() != null
        ? settings.getSyncIntervalMs()
        : syncProperties.intervalMs();
    boolean aiEnabled = settings.getAiEnabled() != null
        ? settings.getAiEnabled()
        : Boolean.TRUE.equals(openAiProperties.enabled());
    String aiModel = settings.getAiModel() != null ? settings.getAiModel() : openAiProperties.model();

    return new AdminSettingsResponse(syncEnabled, syncIntervalMs, aiEnabled, aiModel, settings.getUpdatedAt());
  }
}
