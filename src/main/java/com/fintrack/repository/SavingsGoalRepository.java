package com.fintrack.repository;

import com.fintrack.model.SavingsGoal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavingsGoalRepository extends JpaRepository<SavingsGoal, UUID> {
  List<SavingsGoal> findByUserId(UUID userId);
}
