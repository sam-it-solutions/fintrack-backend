package com.fintrack.service;

import com.fintrack.dto.SavingsGoalRequest;
import com.fintrack.dto.SavingsGoalResponse;
import com.fintrack.model.SavingsGoal;
import com.fintrack.model.User;
import com.fintrack.repository.SavingsGoalRepository;
import com.fintrack.repository.UserRepository;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SavingsGoalService {
  private final SavingsGoalRepository goalRepository;
  private final UserRepository userRepository;

  public SavingsGoalService(SavingsGoalRepository goalRepository, UserRepository userRepository) {
    this.goalRepository = goalRepository;
    this.userRepository = userRepository;
  }

  public List<SavingsGoalResponse> listGoals(UUID userId) {
    List<SavingsGoal> goals = goalRepository.findByUserId(userId);
    applyAutoContributions(goals);
    return goals.stream().map(this::toResponse).toList();
  }

  public SavingsGoalResponse createGoal(UUID userId, SavingsGoalRequest request) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    String name = requireText(request.getName(), "name");
    String currency = requireText(request.getCurrency(), "currency");
    BigDecimal targetAmount = requireAmount(request.getTargetAmount(), "targetAmount");
    boolean autoEnabled = request.isAutoEnabled() == null
        ? request.getMonthlyContribution() != null
        : request.isAutoEnabled();
    SavingsGoal goal = new SavingsGoal();
    goal.setUser(user);
    goal.setName(name);
    goal.setCurrency(currency);
    goal.setTargetAmount(targetAmount);
    goal.setCurrentAmount(defaultAmount(request.getCurrentAmount()));
    goal.setMonthlyContribution(request.getMonthlyContribution());
    goal.setAutoEnabled(autoEnabled);
    SavingsGoal saved = goalRepository.save(goal);
    return toResponse(saved);
  }

  public SavingsGoalResponse updateGoal(UUID userId, UUID goalId, SavingsGoalRequest request) {
    SavingsGoal goal = requireGoal(userId, goalId);
    if (request.getName() != null && !request.getName().isBlank()) {
      goal.setName(request.getName().trim());
    }
    if (request.getCurrency() != null && !request.getCurrency().isBlank()) {
      goal.setCurrency(request.getCurrency().trim());
    }
    if (request.getTargetAmount() != null) {
      goal.setTargetAmount(request.getTargetAmount());
    }
    if (request.getCurrentAmount() != null) {
      goal.setCurrentAmount(request.getCurrentAmount());
    }
    if (request.getMonthlyContribution() != null) {
      goal.setMonthlyContribution(request.getMonthlyContribution());
    }
    if (request.isAutoEnabled() != null) {
      goal.setAutoEnabled(request.isAutoEnabled());
    }
    SavingsGoal saved = goalRepository.save(goal);
    return toResponse(saved);
  }

  public void deleteGoal(UUID userId, UUID goalId) {
    SavingsGoal goal = requireGoal(userId, goalId);
    goalRepository.delete(goal);
  }

  private SavingsGoal requireGoal(UUID userId, UUID goalId) {
    SavingsGoal goal = goalRepository.findById(goalId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Goal not found"));
    if (!goal.getUser().getId().equals(userId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Goal not found");
    }
    return goal;
  }

  private void applyAutoContributions(List<SavingsGoal> goals) {
    YearMonth current = YearMonth.now();
    boolean updated = false;
    for (SavingsGoal goal : goals) {
      if (!goal.isAutoEnabled() || goal.getMonthlyContribution() == null) {
        continue;
      }
      String lastApplied = goal.getLastAppliedMonth();
      YearMonth last = lastApplied == null || lastApplied.isBlank()
          ? current.minusMonths(1)
          : parseMonth(lastApplied, current.minusMonths(1));
      long monthsBetween = ChronoUnit.MONTHS.between(last, current);
      if (monthsBetween <= 0) {
        continue;
      }
      BigDecimal currentAmount = defaultAmount(goal.getCurrentAmount());
      BigDecimal increment = goal.getMonthlyContribution().multiply(BigDecimal.valueOf(monthsBetween));
      BigDecimal updatedAmount = currentAmount.add(increment);
      if (goal.getTargetAmount() != null && goal.getTargetAmount().compareTo(BigDecimal.ZERO) > 0) {
        if (updatedAmount.compareTo(goal.getTargetAmount()) > 0) {
          updatedAmount = goal.getTargetAmount();
        }
      }
      goal.setCurrentAmount(updatedAmount);
      goal.setLastAppliedMonth(current.toString());
      updated = true;
    }
    if (updated) {
      goalRepository.saveAll(goals);
    }
  }

  private YearMonth parseMonth(String value, YearMonth fallback) {
    try {
      return YearMonth.parse(value);
    } catch (Exception ex) {
      return fallback;
    }
  }

  private BigDecimal defaultAmount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private String requireText(String value, String field) {
    if (value == null || value.trim().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
    }
    return value.trim();
  }

  private BigDecimal requireAmount(BigDecimal value, String field) {
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
    }
    return value;
  }

  private SavingsGoalResponse toResponse(SavingsGoal goal) {
    return new SavingsGoalResponse(
        goal.getId(),
        goal.getName(),
        goal.getCurrency(),
        goal.getTargetAmount(),
        goal.getCurrentAmount(),
        goal.getMonthlyContribution(),
        goal.isAutoEnabled(),
        goal.getLastAppliedMonth(),
        goal.getCreatedAt(),
        goal.getUpdatedAt()
    );
  }
}
