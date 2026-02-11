package com.fintrack.controller;

import com.fintrack.dto.AuthResponse;
import com.fintrack.dto.PasskeyFinishRequest;
import com.fintrack.dto.PasskeyLoginStartRequest;
import com.fintrack.dto.PasskeyStartResponse;
import com.fintrack.service.CurrentUserService;
import com.fintrack.service.PasskeyService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/passkeys")
public class PasskeyController {
  private final PasskeyService passkeyService;
  private final CurrentUserService currentUserService;

  public PasskeyController(PasskeyService passkeyService, CurrentUserService currentUserService) {
    this.passkeyService = passkeyService;
    this.currentUserService = currentUserService;
  }

  @PostMapping("/register/start")
  public PasskeyStartResponse startRegistration() {
    UUID userId = currentUserService.requireUserId();
    return passkeyService.startRegistration(userId);
  }

  @PostMapping("/register/finish")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void finishRegistration(@Valid @RequestBody PasskeyFinishRequest request) {
    UUID userId = currentUserService.requireUserId();
    passkeyService.finishRegistration(userId, request.getChallengeId(), request.getCredential());
  }

  @PostMapping("/login/start")
  public PasskeyStartResponse startLogin(@Valid @RequestBody PasskeyLoginStartRequest request) {
    return passkeyService.startAuthentication(request.getEmail());
  }

  @PostMapping("/login/finish")
  public AuthResponse finishLogin(@Valid @RequestBody PasskeyFinishRequest request) {
    return passkeyService.finishAuthentication(request.getChallengeId(), request.getCredential());
  }
}
