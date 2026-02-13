package com.fintrack.service;

import com.fintrack.dto.RecategorizeResponse;
import com.fintrack.dto.RuleRequest;
import com.fintrack.dto.RuleResponse;
import com.fintrack.model.AccountTransaction;
import com.fintrack.model.CategoryOverride;
import com.fintrack.model.HouseholdMember;
import com.fintrack.model.User;
import com.fintrack.repository.AccountTransactionRepository;
import com.fintrack.repository.CategoryOverrideRepository;
import com.fintrack.repository.HouseholdMemberRepository;
import com.fintrack.repository.UserRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RuleService {
  private final CategoryOverrideRepository overrideRepository;
  private final AccountTransactionRepository transactionRepository;
  private final HouseholdMemberRepository householdMemberRepository;
  private final UserRepository userRepository;

  public RuleService(CategoryOverrideRepository overrideRepository,
                     AccountTransactionRepository transactionRepository,
                     HouseholdMemberRepository householdMemberRepository,
                     UserRepository userRepository) {
    this.overrideRepository = overrideRepository;
    this.transactionRepository = transactionRepository;
    this.householdMemberRepository = householdMemberRepository;
    this.userRepository = userRepository;
  }

  public List<RuleResponse> listRules(UUID userId) {
    return overrideRepository.findByUserId(userId).stream()
        .map(this::toResponse)
        .toList();
  }

  public RuleResponse createRule(UUID userId, RuleRequest request) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    String matchType = requireText(request.getMatchType(), "matchType");
    String matchValue = requireText(request.getMatchValue(), "matchValue");
    String category = requireText(request.getCategory(), "category");
    CategoryOverride.MatchMode matchMode = parseMatchMode(request.getMatchMode());
    String normalizedValue = normalize(matchValue);
    if (normalizedValue == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "matchValue is invalid");
    }
    CategoryOverride override = new CategoryOverride();
    override.setUser(user);
    override.setMatchType(parseMatchType(matchType));
    override.setMatchValue(normalizedValue);
    override.setMatchMode(matchMode);
    override.setCategory(category);
    CategoryOverride saved = overrideRepository.save(override);
    if (Boolean.TRUE.equals(request.isApplyToHistory())) {
      applyRuleToHistory(userId, saved.getId());
    }
    return toResponse(saved);
  }

  public RuleResponse updateRule(UUID userId, UUID ruleId, RuleRequest request) {
    CategoryOverride override = requireRule(userId, ruleId);
    if (request.getMatchType() != null && !request.getMatchType().isBlank()) {
      override.setMatchType(parseMatchType(request.getMatchType()));
    }
    if (request.getMatchValue() != null && !request.getMatchValue().isBlank()) {
      override.setMatchValue(normalize(request.getMatchValue()));
    }
    if (request.getMatchMode() != null && !request.getMatchMode().isBlank()) {
      override.setMatchMode(parseMatchMode(request.getMatchMode()));
    }
    if (request.getCategory() != null && !request.getCategory().isBlank()) {
      override.setCategory(request.getCategory().trim());
    }
    override.touch();
    CategoryOverride saved = overrideRepository.save(override);
    return toResponse(saved);
  }

  public void deleteRule(UUID userId, UUID ruleId) {
    CategoryOverride override = requireRule(userId, ruleId);
    overrideRepository.delete(override);
  }

  public RecategorizeResponse applyRuleToHistory(UUID userId, UUID ruleId) {
    CategoryOverride override = requireRule(userId, ruleId);
    List<UUID> householdIds = householdMemberRepository.findByUserId(userId).stream()
        .map(m -> m.getHousehold().getId())
        .toList();
    List<AccountTransaction> transactions = householdIds.isEmpty()
        ? transactionRepository.findUserTransactions(userId)
        : transactionRepository.findUserAndHouseholdTransactions(userId, householdIds);

    int updated = 0;
    List<AccountTransaction> toSave = new ArrayList<>();
    for (AccountTransaction tx : transactions) {
      if (!matches(override, tx)) {
        continue;
      }
      boolean changed = !override.getCategory().equalsIgnoreCase(tx.getCategory() == null ? "" : tx.getCategory());
      tx.setCategory(override.getCategory());
      tx.setCategorySource("override");
      tx.setCategoryReason("Gebruikersregel");
      tx.setCategoryConfidence(BigDecimal.valueOf(0.98));
      if (changed) {
        updated++;
      }
      toSave.add(tx);
    }
    if (!toSave.isEmpty()) {
      transactionRepository.saveAll(toSave);
    }
    return new RecategorizeResponse(updated, transactions.size(), 0);
  }

  private CategoryOverride requireRule(UUID userId, UUID ruleId) {
    CategoryOverride override = overrideRepository.findById(ruleId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found"));
    if (!override.getUser().getId().equals(userId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found");
    }
    return override;
  }

  private CategoryOverride.MatchType parseMatchType(String value) {
    try {
      return CategoryOverride.MatchType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid match type");
    }
  }

  private CategoryOverride.MatchMode parseMatchMode(String value) {
    if (value == null || value.isBlank()) {
      return CategoryOverride.MatchMode.CONTAINS;
    }
    try {
      return CategoryOverride.MatchMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid match mode");
    }
  }

  private boolean matches(CategoryOverride override, AccountTransaction tx) {
    CategoryOverride.MatchMode mode = override.getMatchMode() == null
        ? CategoryOverride.MatchMode.CONTAINS
        : override.getMatchMode();
    return switch (override.getMatchType()) {
      case IBAN -> equalsNormalized(tx.getCounterpartyIban(), override.getMatchValue());
      case MERCHANT -> mode == CategoryOverride.MatchMode.EXACT
          ? equalsNormalized(tx.getMerchantName(), override.getMatchValue())
          : containsNormalized(tx.getMerchantName(), override.getMatchValue());
      case DESCRIPTION -> mode == CategoryOverride.MatchMode.EXACT
          ? equalsNormalized(tx.getDescription(), override.getMatchValue())
          : containsNormalized(tx.getDescription(), override.getMatchValue());
    };
  }

  private boolean equalsNormalized(String value, String matchValue) {
    String normalized = normalize(value);
    return normalized != null && normalized.equals(matchValue);
  }

  private boolean containsNormalized(String value, String matchValue) {
    String normalized = normalize(value);
    return normalized != null && normalized.contains(matchValue);
  }

  private String normalize(String value) {
    if (value == null) {
      return null;
    }
    String cleaned = value.toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", " ")
        .replaceAll("[^a-z0-9]+", "")
        .trim();
    return cleaned.isBlank() ? null : cleaned;
  }

  private String requireText(String value, String field) {
    if (value == null || value.trim().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
    }
    return value.trim();
  }

  private RuleResponse toResponse(CategoryOverride override) {
    return new RuleResponse(
        override.getId(),
        override.getMatchType().name(),
        override.getMatchValue(),
        (override.getMatchMode() == null ? CategoryOverride.MatchMode.CONTAINS : override.getMatchMode()).name(),
        override.getCategory(),
        override.getCreatedAt(),
        override.getUpdatedAt()
    );
  }
}
