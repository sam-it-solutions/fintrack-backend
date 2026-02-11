package com.fintrack.controller;

import com.fintrack.dto.AuthRequest;
import com.fintrack.dto.AuthResponse;
import com.fintrack.model.User;
import com.fintrack.service.JwtService;
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

  public AuthController(UserService userService, JwtService jwtService) {
    this.userService = userService;
    this.jwtService = jwtService;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public AuthResponse register(@Valid @RequestBody AuthRequest request) {
    User user = userService.register(request.getEmail(), request.getPassword());
    String token = jwtService.generateToken(user.getId(), user.getEmail());
    return new AuthResponse(token, user.getId());
  }

  @PostMapping("/login")
  public AuthResponse login(@Valid @RequestBody AuthRequest request) {
    User user = userService.authenticate(request.getEmail(), request.getPassword());
    String token = jwtService.generateToken(user.getId(), user.getEmail());
    return new AuthResponse(token, user.getId());
  }
}
