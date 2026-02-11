package com.fintrack.controller;

import com.fintrack.config.AppProperties;
import com.fintrack.dto.ConnectResponse;
import com.fintrack.dto.ConnectionResponse;
import com.fintrack.dto.CreateConnectionRequest;
import com.fintrack.dto.EnableBankingAspspResponse;
import com.fintrack.dto.ProviderResponse;
import com.fintrack.dto.UpdateConnectionRequest;
import com.fintrack.service.ConnectionService;
import com.fintrack.service.CurrentUserService;
import com.fintrack.service.EnableBankingService;
import com.fintrack.service.TinkService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ConnectionController {
  private final ConnectionService connectionService;
  private final CurrentUserService currentUserService;
  private final AppProperties appProperties;
  private final TinkService tinkService;
  private final EnableBankingService enableBankingService;

  public ConnectionController(ConnectionService connectionService,
                              CurrentUserService currentUserService,
                              AppProperties appProperties,
                              TinkService tinkService,
                              EnableBankingService enableBankingService) {
    this.connectionService = connectionService;
    this.currentUserService = currentUserService;
    this.appProperties = appProperties;
    this.tinkService = tinkService;
    this.enableBankingService = enableBankingService;
  }

  @GetMapping("/providers")
  public List<ProviderResponse> listProviders() {
    return connectionService.listProviders();
  }

  @GetMapping("/providers/enablebanking/aspsps")
  public List<EnableBankingAspspResponse> listEnableBankingAspsps(
      @RequestParam(name = "country", defaultValue = "BE") String country,
      @RequestParam(name = "psuType", defaultValue = "personal") String psuType) {
    return enableBankingService.listAspsps(country, psuType);
  }

  @GetMapping("/providers/tink/callback")
  public ResponseEntity<Void> tinkCallback(@RequestParam(value = "code", required = false) String code,
                                           @RequestParam(value = "state", required = false) String state,
                                           @RequestParam(value = "connectionId", required = false) UUID connectionId,
                                           @RequestParam(value = "error", required = false) String error,
                                           @RequestParam(value = "error_description", required = false) String errorDescription) {
    UUID resolvedId = connectionId;
    if (resolvedId == null && state != null && !state.isBlank()) {
      resolvedId = UUID.fromString(state);
    }
    if (resolvedId == null) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
    if (code == null || code.isBlank()) {
      String redirectTo = appProperties.frontendUrl() == null
          ? "http://localhost:4200"
          : appProperties.frontendUrl();
      String location = redirectTo + "?callback=error"
          + "&reason=" + (error == null ? "missing_code" : error)
          + "&detail=" + (errorDescription == null ? "" : errorDescription);
      return ResponseEntity.status(HttpStatus.FOUND)
          .header(HttpHeaders.LOCATION, location)
          .build();
    }
    tinkService.handleCallback(resolvedId, code);
    String redirectTo = appProperties.frontendUrl() == null
        ? "http://localhost:4200"
        : appProperties.frontendUrl();
    String location = redirectTo + "?callback=success";
    return ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.LOCATION, location)
        .build();
  }

  @GetMapping("/providers/enablebanking/callback")
  public ResponseEntity<Void> enableBankingCallback(@RequestParam(value = "code", required = false) String code,
                                                    @RequestParam(value = "state", required = false) String state,
                                                    @RequestParam(value = "connectionId", required = false) UUID connectionId,
                                                    @RequestParam(value = "error", required = false) String error,
                                                    @RequestParam(value = "error_description", required = false) String errorDescription) {
    UUID resolvedId = connectionId;
    if (resolvedId == null && state != null && !state.isBlank()) {
      resolvedId = UUID.fromString(state);
    }
    if (resolvedId == null) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
    if (code == null || code.isBlank()) {
      String redirectTo = appProperties.frontendUrl() == null
          ? "http://localhost:4200"
          : appProperties.frontendUrl();
      String location = redirectTo + "?callback=error"
          + "&reason=" + (error == null ? "missing_code" : error)
          + "&detail=" + (errorDescription == null ? "" : errorDescription);
      return ResponseEntity.status(HttpStatus.FOUND)
          .header(HttpHeaders.LOCATION, location)
          .build();
    }
    enableBankingService.handleCallback(resolvedId, code);
    String redirectTo = appProperties.frontendUrl() == null
        ? "http://localhost:4200"
        : appProperties.frontendUrl();
    String location = redirectTo + "?callback=success";
    return ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.LOCATION, location)
        .build();
  }

  @PostMapping("/connections")
  @ResponseStatus(HttpStatus.CREATED)
  public ConnectionResponse create(@Valid @RequestBody CreateConnectionRequest request) {
    UUID userId = currentUserService.requireUserId();
    return connectionService.createConnection(userId, request);
  }

  @GetMapping("/connections")
  public List<ConnectionResponse> list() {
    UUID userId = currentUserService.requireUserId();
    return connectionService.listConnections(userId);
  }

  @PatchMapping("/connections/{connectionId}")
  public ConnectionResponse update(@PathVariable UUID connectionId,
                                   @RequestBody UpdateConnectionRequest request) {
    UUID userId = currentUserService.requireUserId();
    return connectionService.updateConnection(userId, connectionId, request);
  }

  @PostMapping("/connections/{connectionId}/initiate")
  public ConnectResponse initiate(@PathVariable UUID connectionId) {
    UUID userId = currentUserService.requireUserId();
    return connectionService.initiateConnection(userId, connectionId);
  }

  @PostMapping("/connections/{connectionId}/sync")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ConnectionResponse sync(@PathVariable UUID connectionId) {
    UUID userId = currentUserService.requireUserId();
    return connectionService.syncConnection(userId, connectionId);
  }

  @DeleteMapping("/connections/{connectionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID connectionId) {
    UUID userId = currentUserService.requireUserId();
    connectionService.disableConnection(userId, connectionId);
  }
}
