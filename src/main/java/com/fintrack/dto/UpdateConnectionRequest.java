package com.fintrack.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateConnectionRequest {
  private String displayName;
  private Boolean autoSyncEnabled;
}
