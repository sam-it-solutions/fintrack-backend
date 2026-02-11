package com.fintrack.controller;

import com.fintrack.dto.RecategorizeResponse;
import com.fintrack.dto.RuleRequest;
import com.fintrack.dto.RuleResponse;
import com.fintrack.service.CurrentUserService;
import com.fintrack.service.RuleService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/finance/rules")
public class RuleController {
  private final RuleService ruleService;
  private final CurrentUserService currentUserService;

  public RuleController(RuleService ruleService, CurrentUserService currentUserService) {
    this.ruleService = ruleService;
    this.currentUserService = currentUserService;
  }

  @GetMapping
  public List<RuleResponse> listRules() {
    UUID userId = currentUserService.requireUserId();
    return ruleService.listRules(userId);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RuleResponse createRule(@Valid @RequestBody RuleRequest request) {
    UUID userId = currentUserService.requireUserId();
    return ruleService.createRule(userId, request);
  }

  @PatchMapping("/{ruleId}")
  public RuleResponse updateRule(@PathVariable UUID ruleId, @Valid @RequestBody RuleRequest request) {
    UUID userId = currentUserService.requireUserId();
    return ruleService.updateRule(userId, ruleId, request);
  }

  @DeleteMapping("/{ruleId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteRule(@PathVariable UUID ruleId) {
    UUID userId = currentUserService.requireUserId();
    ruleService.deleteRule(userId, ruleId);
  }

  @PostMapping("/{ruleId}/apply")
  public RecategorizeResponse applyRule(@PathVariable UUID ruleId) {
    UUID userId = currentUserService.requireUserId();
    return ruleService.applyRuleToHistory(userId, ruleId);
  }
}
