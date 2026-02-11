package com.fintrack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fintrack.mail")
public record MailProperties(boolean enabled, String from, String subjectPrefix) {}
