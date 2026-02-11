package com.fintrack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fintrack.providers.bitvavo")
public record BitvavoProperties(String baseUrl, String apiKey, String apiSecret) {}
