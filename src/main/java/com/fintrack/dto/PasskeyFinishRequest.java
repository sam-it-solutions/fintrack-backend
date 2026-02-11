package com.fintrack.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public class PasskeyFinishRequest {
  private UUID challengeId;
  private JsonNode credential;

  public UUID getChallengeId() {
    return challengeId;
  }

  public void setChallengeId(UUID challengeId) {
    this.challengeId = challengeId;
  }

  public JsonNode getCredential() {
    return credential;
  }

  public void setCredential(JsonNode credential) {
    this.credential = credential;
  }
}
