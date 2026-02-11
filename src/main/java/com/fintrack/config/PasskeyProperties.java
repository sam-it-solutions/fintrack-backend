package com.fintrack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fintrack.passkeys")
public record PasskeyProperties(String rpId, String rpName, String origin) {}
