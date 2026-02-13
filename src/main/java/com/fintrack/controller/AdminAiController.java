package com.fintrack.controller;

import com.fintrack.dto.AiKeyTestResponse;
import com.fintrack.service.OpenAiClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/ai")
public class AdminAiController {
  private final OpenAiClient openAiClient;

  public AdminAiController(OpenAiClient openAiClient) {
    this.openAiClient = openAiClient;
  }

  @GetMapping("/test")
  public AiKeyTestResponse testKey() {
    return openAiClient.testApiKey();
  }
}
