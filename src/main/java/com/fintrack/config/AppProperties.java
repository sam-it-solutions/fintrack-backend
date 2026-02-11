package com.fintrack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fintrack.app")
public record AppProperties(String frontendUrl, String backendUrl) {}
