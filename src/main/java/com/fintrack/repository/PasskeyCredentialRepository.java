package com.fintrack.repository;

import com.fintrack.model.PasskeyCredential;
import com.fintrack.model.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasskeyCredentialRepository extends JpaRepository<PasskeyCredential, UUID> {
  Optional<PasskeyCredential> findByCredentialId(String credentialId);
  List<PasskeyCredential> findByUser(User user);
  List<PasskeyCredential> findAllByCredentialId(String credentialId);
}
