package com.fintrack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fintrack.jwt")
public record JwtProperties(String secret, String issuer, long ttlMinutes) {}
