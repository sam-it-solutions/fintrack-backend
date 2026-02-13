package com.fintrack.service;

import com.fintrack.model.RefreshToken;
import com.fintrack.repository.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RefreshTokenService {
  private static final Duration REFRESH_TTL = Duration.ofDays(30);
  private final RefreshTokenRepository repository;
  private final SecureRandom random = new SecureRandom();

  public RefreshTokenService(RefreshTokenRepository repository) {
    this.repository = repository;
  }

  public TokenResult issue(UUID userId) {
    String token = generateToken();
    String hash = hash(token);
    RefreshToken entity = new RefreshToken();
    entity.setUserId(userId);
    entity.setTokenHash(hash);
    entity.setExpiresAt(Instant.now().plus(REFRESH_TTL));
    repository.save(entity);
    return new TokenResult(userId, token, entity.getExpiresAt());
  }

  public TokenResult rotate(String refreshToken) {
    RefreshToken existing = repository.findByTokenHash(hash(refreshToken))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));
    if (existing.getRevokedAt() != null || existing.getExpiresAt().isBefore(Instant.now())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
    }
    existing.setRevokedAt(Instant.now());
    repository.save(existing);
    return issue(existing.getUserId());
  }

  public void revoke(String refreshToken) {
    RefreshToken existing = repository.findByTokenHash(hash(refreshToken))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Refresh token not found"));
    existing.setRevokedAt(Instant.now());
    repository.save(existing);
  }

  private String generateToken() {
    byte[] bytes = new byte[64];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to hash refresh token", ex);
    }
  }

  public record TokenResult(UUID userId, String token, Instant expiresAt) {}
}
