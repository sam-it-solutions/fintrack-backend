package com.fintrack.service;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CurrentUserService {
  public UUID requireUserId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || authentication.getPrincipal() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication");
    }
    Object principal = authentication.getPrincipal();
    if (principal instanceof UUID) {
      return (UUID) principal;
    }
    if (principal instanceof String) {
      return UUID.fromString((String) principal);
    }
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authentication");
  }
}
