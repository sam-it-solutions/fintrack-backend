package com.fintrack.service;

import com.fintrack.config.GeminiProperties;
import com.fintrack.config.OpenAiProperties;
import com.fintrack.config.SyncProperties;
import com.fintrack.dto.AdminSettingsRequest;
import com.fintrack.dto.AdminSettingsResponse;
import com.fintrack.model.AppSettings;
import com.fintrack.repository.AppSettingsRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AppSettingsService {
  private static final long MIN_SYNC_INTERVAL_MS = 5 * 60 * 1000L;
  private static final long MAX_SYNC_INTERVAL_MS = 24 * 60 * 60 * 1000L;
  private static final long MIN_CRYPTO_SYNC_INTERVAL_MS = 60 * 1000L;
  private static final long MAX_CRYPTO_SYNC_INTERVAL_MS = 24 * 60 * 60 * 1000L;

  private final AppSettingsRepository repository;
  private final SyncProperties syncProperties;
  private final OpenAiProperties openAiProperties;
  private final GeminiProperties geminiProperties;

  public AppSettingsService(AppSettingsRepository repository,
                            SyncProperties syncProperties,
                            OpenAiProperties openAiProperties,
                            GeminiProperties geminiProperties) {
    this.repository = repository;
    this.syncProperties = syncProperties;
    this.openAiProperties = openAiProperties;
    this.geminiProperties = geminiProperties;
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
    if (request.getCryptoSyncIntervalMs() != null) {
      long normalized = Math.max(MIN_CRYPTO_SYNC_INTERVAL_MS,
          Math.min(MAX_CRYPTO_SYNC_INTERVAL_MS, request.getCryptoSyncIntervalMs()));
      settings.setCryptoSyncIntervalMs(normalized);
    }
    if (request.getAiEnabled() != null) {
      settings.setAiEnabled(request.getAiEnabled());
      if (Boolean.TRUE.equals(request.getAiEnabled())) {
        settings.setAiDisabledUntil(null);
        settings.setAiLastError(null);
        settings.setAiLastErrorAt(null);
      }
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

  public long getCryptoSyncIntervalMs() {
    long baseInterval = getSyncIntervalMs();
    AppSettings settings = getOrCreate();
    Long override = settings.getCryptoSyncIntervalMs();
    if (override != null && override > 0) {
      return override;
    }
    long cryptoDefault = syncProperties.cryptoIntervalMs();
    if (cryptoDefault <= 0) {
      return baseInterval;
    }
    return Math.min(baseInterval, cryptoDefault);
  }

  public boolean isAiEnabled() {
    AppSettings settings = getOrCreate();
    Boolean enabled = settings.getAiEnabled();
    return enabled != null ? enabled : defaultAiEnabled();
  }

  public boolean isAiAvailable() {
    if (!isAiEnabled()) {
      return false;
    }
    AppSettings settings = getOrCreate();
    Instant disabledUntil = settings.getAiDisabledUntil();
    return disabledUntil == null || disabledUntil.isBefore(Instant.now());
  }

  public void recordAiFailure(String message, Duration cooldown) {
    if (cooldown == null || cooldown.isNegative() || cooldown.isZero()) {
      return;
    }
    AppSettings settings = getOrCreate();
    Instant now = Instant.now();
    Instant nextDisabledUntil = now.plus(cooldown);
    Instant currentDisabledUntil = settings.getAiDisabledUntil();
    if (currentDisabledUntil == null || currentDisabledUntil.isBefore(nextDisabledUntil)) {
      settings.setAiDisabledUntil(nextDisabledUntil);
    }
    if (message != null && !message.isBlank()) {
      settings.setAiLastError(message);
      settings.setAiLastErrorAt(now);
    }
    repository.save(settings);
  }

  public String getAiModel() {
    AppSettings settings = getOrCreate();
    return normalizeAiModel(settings.getAiModel());
  }

  private AppSettings getOrCreate() {
    Optional<AppSettings> existing = repository.findFirstByOrderByCreatedAtAsc();
    if (existing.isPresent()) {
      return existing.get();
    }
    AppSettings created = new AppSettings();
    created.setSyncEnabled(syncProperties.enabled());
    created.setSyncIntervalMs(syncProperties.intervalMs());
    created.setAiEnabled(defaultAiEnabled());
    created.setAiModel(defaultAiModel());
    return repository.save(created);
  }

  private AdminSettingsResponse toResponse(AppSettings settings) {
    boolean syncEnabled = settings.getSyncEnabled() != null
        ? settings.getSyncEnabled()
        : syncProperties.enabled();
    long syncIntervalMs = settings.getSyncIntervalMs() != null
        ? settings.getSyncIntervalMs()
        : syncProperties.intervalMs();
    long cryptoSyncIntervalMs;
    if (settings.getCryptoSyncIntervalMs() != null) {
      cryptoSyncIntervalMs = settings.getCryptoSyncIntervalMs();
    } else {
      long cryptoDefault = syncProperties.cryptoIntervalMs();
      cryptoSyncIntervalMs = cryptoDefault <= 0 ? syncIntervalMs : Math.min(syncIntervalMs, cryptoDefault);
    }
    boolean aiEnabled = settings.getAiEnabled() != null
        ? settings.getAiEnabled()
        : defaultAiEnabled();
    String aiModel = normalizeAiModel(settings.getAiModel());
    return new AdminSettingsResponse(
        syncEnabled,
        syncIntervalMs,
        cryptoSyncIntervalMs,
        aiEnabled,
        aiModel,
        settings.getAiDisabledUntil(),
        settings.getAiLastError(),
        settings.getAiLastErrorAt(),
        settings.getUpdatedAt()
    );
  }


  private String normalizeAiModel(String model) {
    if (model != null && !model.isBlank()) {
      String trimmed = model.trim();
      if (trimmed.toLowerCase(Locale.ROOT).startsWith("gemini")) {
        return trimmed;
      }
    }
    return defaultAiModel();
  }

  private boolean defaultAiEnabled() {
    return Boolean.TRUE.equals(geminiProperties.enabled()) || Boolean.TRUE.equals(openAiProperties.enabled());
  }

  private String defaultAiModel() {
    if (geminiProperties.model() != null && !geminiProperties.model().isBlank()) {
      return geminiProperties.model();
    }
    return openAiProperties.model();
  }
}
