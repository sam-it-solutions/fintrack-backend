package com.fintrack.repository;

import com.fintrack.model.Household;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseholdRepository extends JpaRepository<Household, UUID> {
  Optional<Household> findByInviteCode(String inviteCode);
}
