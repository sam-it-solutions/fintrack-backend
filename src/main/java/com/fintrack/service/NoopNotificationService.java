package com.fintrack.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "fintrack.mail", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopNotificationService implements NotificationService {
  @Override
  public void send(String to, String subject, String body) {
    // No-op by default
  }
}
