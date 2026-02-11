package com.fintrack.controller;

import com.fintrack.dto.SavingsGoalRequest;
import com.fintrack.dto.SavingsGoalResponse;
import com.fintrack.service.CurrentUserService;
import com.fintrack.service.SavingsGoalService;
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
@RequestMapping("/api/finance/goals")
public class SavingsGoalController {
  private final SavingsGoalService goalService;
  private final CurrentUserService currentUserService;

  public SavingsGoalController(SavingsGoalService goalService, CurrentUserService currentUserService) {
    this.goalService = goalService;
    this.currentUserService = currentUserService;
  }

  @GetMapping
  public List<SavingsGoalResponse> listGoals() {
    UUID userId = currentUserService.requireUserId();
    return goalService.listGoals(userId);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public SavingsGoalResponse createGoal(@Valid @RequestBody SavingsGoalRequest request) {
    UUID userId = currentUserService.requireUserId();
    return goalService.createGoal(userId, request);
  }

  @PatchMapping("/{goalId}")
  public SavingsGoalResponse updateGoal(@PathVariable UUID goalId, @RequestBody SavingsGoalRequest request) {
    UUID userId = currentUserService.requireUserId();
    return goalService.updateGoal(userId, goalId, request);
  }

  @DeleteMapping("/{goalId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteGoal(@PathVariable UUID goalId) {
    UUID userId = currentUserService.requireUserId();
    goalService.deleteGoal(userId, goalId);
  }
}
