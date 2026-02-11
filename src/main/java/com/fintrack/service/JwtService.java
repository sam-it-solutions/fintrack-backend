package com.fintrack.service;

import com.fintrack.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
  private final JwtProperties properties;
  private final SecretKey key;

  public JwtService(JwtProperties properties) {
    this.properties = properties;
    this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
  }

  public String generateToken(UUID userId, String email) {
    Instant now = Instant.now();
    Instant expiry = now.plusSeconds(properties.ttlMinutes() * 60L);

    return Jwts.builder()
        .setSubject(userId.toString())
        .setIssuer(properties.issuer())
        .setIssuedAt(Date.from(now))
        .setExpiration(Date.from(expiry))
        .claim("email", email)
        .signWith(key, SignatureAlgorithm.HS256)
        .compact();
  }

  public UUID parseUserId(String token) {
    Claims claims = Jwts.parserBuilder()
        .setSigningKey(key)
        .build()
        .parseClaimsJws(token)
        .getBody();
    return UUID.fromString(claims.getSubject());
  }
}
