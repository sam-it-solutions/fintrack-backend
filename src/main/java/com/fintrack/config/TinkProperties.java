package com.fintrack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fintrack.providers.tink")
public record TinkProperties(
    String baseUrl,
    String linkUrlTemplate,
    String clientId,
    String clientSecret,
    String redirectUrl,
    String market,
    String locale,
    String scope,
    String tokenPath,
    String accountsPath,
    String transactionsPath,
    String dataContentType,
    Boolean debugLogResponses
) {}
