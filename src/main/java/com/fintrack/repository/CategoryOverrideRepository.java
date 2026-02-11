package com.fintrack.repository;

import com.fintrack.model.CategoryOverride;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryOverrideRepository extends JpaRepository<CategoryOverride, UUID> {
  Optional<CategoryOverride> findFirstByUserIdAndMatchTypeAndMatchValue(
      UUID userId,
      CategoryOverride.MatchType matchType,
      String matchValue);

  List<CategoryOverride> findByUserIdAndMatchType(UUID userId, CategoryOverride.MatchType matchType);

  List<CategoryOverride> findByUserId(UUID userId);
}
