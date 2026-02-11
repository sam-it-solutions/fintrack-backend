package com.fintrack.service;

import com.fintrack.config.MailProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "fintrack.mail", name = "enabled", havingValue = "true")
public class MailNotificationService implements NotificationService {
  private final JavaMailSender mailSender;
  private final MailProperties properties;

  public MailNotificationService(JavaMailSender mailSender, MailProperties properties) {
    this.mailSender = mailSender;
    this.properties = properties;
  }

  @Override
  public void send(String to, String subject, String body) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setTo(to);
    if (properties.from() != null && !properties.from().isBlank()) {
      message.setFrom(properties.from());
    }
    String prefix = properties.subjectPrefix() == null ? "" : properties.subjectPrefix();
    message.setSubject(prefix + subject);
    message.setText(body);
    mailSender.send(message);
  }
}
