package com.fintrack.controller;

import com.fintrack.dto.AdminSettingsRequest;
import com.fintrack.dto.AdminSettingsResponse;
import com.fintrack.service.AppSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/settings")
public class AdminSettingsController {
  private final AppSettingsService appSettingsService;

  public AdminSettingsController(AppSettingsService appSettingsService) {
    this.appSettingsService = appSettingsService;
  }

  @GetMapping
  public AdminSettingsResponse getSettings() {
    return appSettingsService.getSettings();
  }

  @PutMapping
  public AdminSettingsResponse updateSettings(@RequestBody AdminSettingsRequest request) {
    return appSettingsService.updateSettings(request);
  }
}
