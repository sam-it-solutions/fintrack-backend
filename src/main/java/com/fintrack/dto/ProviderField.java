package com.fintrack.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ProviderField {
  private String key;
  private String label;
  private boolean required;
  private boolean secret;
  private String placeholder;
}
