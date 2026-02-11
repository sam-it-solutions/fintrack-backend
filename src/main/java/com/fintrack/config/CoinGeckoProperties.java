package com.fintrack.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fintrack.providers.coingecko")
public record CoinGeckoProperties(
    String baseUrl,
    String apiKey,
    String apiKeyHeader,
    Duration cacheTtl
) {}
