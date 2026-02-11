package com.fintrack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fintrack.providers.enablebanking")
public record EnableBankingProperties(
    String baseUrl,
    String environment,
    String sandboxAppId,
    String productionAppId,
    String redirectUrl,
    String privateKeyPath,
    String privateKeyProdPath,
    String defaultCountry,
    String defaultPsuType,
    String defaultLanguage,
    Integer consentDays,
    Boolean debugLogResponses
) {}
