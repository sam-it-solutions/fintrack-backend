package com.fintrack.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AuthResponse {
  private String token;
  private UUID userId;
  private String refreshToken;
  private Instant refreshTokenExpiresAt;
}
