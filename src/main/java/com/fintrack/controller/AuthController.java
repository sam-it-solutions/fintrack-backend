package com.fintrack.controller;

import com.fintrack.dto.AuthRequest;
import com.fintrack.dto.AuthResponse;
import com.fintrack.dto.RefreshTokenRequest;
import com.fintrack.model.User;
import com.fintrack.service.JwtService;
import com.fintrack.service.RefreshTokenService;
import com.fintrack.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final UserService userService;
  private final JwtService jwtService;
  private final RefreshTokenService refreshTokenService;

  public AuthController(UserService userService, JwtService jwtService, RefreshTokenService refreshTokenService) {
    this.userService = userService;
    this.jwtService = jwtService;
    this.refreshTokenService = refreshTokenService;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public AuthResponse register(@Valid @RequestBody AuthRequest request) {
    User user = userService.register(request.getEmail(), request.getPassword());
    String token = jwtService.generateToken(user.getId(), user.getEmail());
    RefreshTokenService.TokenResult refresh = refreshTokenService.issue(user.getId());
    return new AuthResponse(token, user.getId(), refresh.token(), refresh.expiresAt());
  }

  @PostMapping("/login")
  public AuthResponse login(@Valid @RequestBody AuthRequest request) {
    User user = userService.authenticate(request.getEmail(), request.getPassword());
    String token = jwtService.generateToken(user.getId(), user.getEmail());
    RefreshTokenService.TokenResult refresh = refreshTokenService.issue(user.getId());
    return new AuthResponse(token, user.getId(), refresh.token(), refresh.expiresAt());
  }

  @PostMapping("/refresh")
  public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
    RefreshTokenService.TokenResult refresh = refreshTokenService.rotate(request.getRefreshToken());
    User user = userService.findById(refresh.userId());
    String token = jwtService.generateToken(user.getId(), user.getEmail());
    return new AuthResponse(token, user.getId(), refresh.token(), refresh.expiresAt());
  }

  @PostMapping("/logout")
  public void logout(@Valid @RequestBody RefreshTokenRequest request) {
    refreshTokenService.revoke(request.getRefreshToken());
  }
}
