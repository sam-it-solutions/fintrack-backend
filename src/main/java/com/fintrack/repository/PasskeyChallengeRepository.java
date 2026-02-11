package com.fintrack.repository;

import com.fintrack.model.PasskeyChallenge;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasskeyChallengeRepository extends JpaRepository<PasskeyChallenge, UUID> {
  Optional<PasskeyChallenge> findByIdAndType(UUID id, String type);
}
