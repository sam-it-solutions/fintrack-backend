package com.fintrack.dto;

import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateConnectionRequest {
  private String displayName;
  private Boolean autoSyncEnabled;
  private Map<String, String> config;
}
