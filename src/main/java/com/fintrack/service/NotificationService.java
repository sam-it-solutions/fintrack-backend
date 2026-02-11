package com.fintrack.service;

public interface NotificationService {
  void send(String to, String subject, String body);
}
