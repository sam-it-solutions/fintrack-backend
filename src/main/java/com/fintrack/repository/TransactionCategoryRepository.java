package com.fintrack.repository;

import com.fintrack.model.TransactionCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionCategoryRepository extends JpaRepository<TransactionCategory, UUID> {
  List<TransactionCategory> findByUserIdOrderByNameAsc(UUID userId);
  Optional<TransactionCategory> findByUserIdAndNameIgnoreCase(UUID userId, String name);
}
