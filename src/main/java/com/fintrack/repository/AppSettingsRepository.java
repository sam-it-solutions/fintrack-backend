package com.fintrack.repository;

import com.fintrack.model.AppSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingsRepository extends JpaRepository<AppSettings, UUID> {
  Optional<AppSettings> findFirstByOrderByCreatedAtAsc();
}
