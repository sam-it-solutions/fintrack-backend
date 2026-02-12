package com.fintrack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fintrack.sync")
public record SyncProperties(boolean enabled, long intervalMs, long cryptoIntervalMs) {}
