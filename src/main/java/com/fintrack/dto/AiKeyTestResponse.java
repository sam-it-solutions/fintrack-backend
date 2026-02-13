package com.fintrack.dto;

public class AiKeyTestResponse {
  private boolean ok;
  private String status;
  private String message;

  public AiKeyTestResponse() {}

  public AiKeyTestResponse(boolean ok, String status, String message) {
    this.ok = ok;
    this.status = status;
    this.message = message;
  }

  public boolean isOk() {
    return ok;
  }

  public void setOk(boolean ok) {
    this.ok = ok;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }
}
