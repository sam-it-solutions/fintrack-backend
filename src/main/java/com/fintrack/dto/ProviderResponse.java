package com.fintrack.dto;

import com.fintrack.model.ConnectionType;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ProviderResponse {
  private String id;
  private String name;
  private ConnectionType type;
  private boolean requiresAuth;
  private List<ProviderField> fields;
}
