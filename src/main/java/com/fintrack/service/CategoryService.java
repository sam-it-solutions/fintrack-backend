package com.fintrack.service;

import com.fintrack.model.AccountType;
import com.fintrack.model.CategoryOverride;
import com.fintrack.model.FinancialAccount;
import com.fintrack.model.TransactionDirection;
import com.fintrack.repository.CategoryOverrideRepository;
import com.fintrack.repository.FinancialAccountRepository;
import com.fintrack.repository.TransactionCategoryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class CategoryService {
  private static final List<String> CATEGORIES = List.of(
      "Boodschappen",
      "Horeca",
      "Transport",
      "Shopping",
      "Abonnementen",
      "Utilities",
      "Huur/Hypotheek",
      "Gezondheid",
      "Onderwijs",
      "Cash",
      "Transfer",
      "Inkomen",
      "Crypto",
      "Overig"
  );
  private static final List<String> CRYPTO_KEYWORDS = List.of(
      "bitvavo",
      "coinbase",
      "kraken",
      "binance",
      "bitstamp",
      "kucoin",
      "gateio",
      "okx",
      "bybit",
      "cryptocom",
      "bitpanda",
      "coinmerce",
      "bitonic",
      "btcdirect"
  );
  private static final Duration ACCOUNT_CACHE_TTL = Duration.ofMinutes(5);
  private static final Pattern IBAN_PATTERN = Pattern.compile("\\b[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}\\b");

  private final OpenAiClient openAiClient;
  private final TransactionCategorizer ruleCategorizer;
  private final CategoryOverrideRepository overrideRepository;
  private final TransactionCategoryRepository transactionCategoryRepository;
  private final FinancialAccountRepository accountRepository;
  private final ConcurrentHashMap<UUID, CachedAccounts> accountCache = new ConcurrentHashMap<>();

  public CategoryService(OpenAiClient openAiClient,
                         TransactionCategorizer ruleCategorizer,
                         CategoryOverrideRepository overrideRepository,
                         TransactionCategoryRepository transactionCategoryRepository,
                         FinancialAccountRepository accountRepository) {
    this.openAiClient = openAiClient;
    this.ruleCategorizer = ruleCategorizer;
    this.overrideRepository = overrideRepository;
    this.transactionCategoryRepository = transactionCategoryRepository;
    this.accountRepository = accountRepository;
  }

  public String categorize(UUID userId,
                           String description,
                           String merchant,
                           TransactionDirection direction,
                           String transactionType,
                           AccountType accountType,
                           String currency,
                           String amount,
                           String counterpartyIban) {
    return categorizeDetailed(userId, description, merchant, direction, transactionType, accountType, currency, amount, counterpartyIban).category();
  }

  public CategoryResult categorizeDetailed(UUID userId,
                                           String description,
                                           String merchant,
                                           TransactionDirection direction,
                                           String transactionType,
                                           AccountType accountType,
                                           String currency,
                                           String amount,
                                           String counterpartyIban) {
    return categorizeDetailed(userId, description, merchant, direction, transactionType, accountType, currency, amount, counterpartyIban, true);
  }

  public CategoryResult categorizeDetailed(UUID userId,
                                           String description,
                                           String merchant,
                                           TransactionDirection direction,
                                           String transactionType,
                                           AccountType accountType,
                                           String currency,
                                           String amount,
                                           String counterpartyIban,
                                           boolean allowAi) {
    if (accountType == AccountType.CRYPTO) {
      return new CategoryResult("Crypto", "rule", 0.95, "Crypto account");
    }
    String detectedIban = firstNonBlank(
        counterpartyIban,
        extractIban(description),
        extractIban(merchant));
    if (isInternalTransfer(userId, detectedIban)) {
      return new CategoryResult("Transfer", "rule", 0.92, "Eigen rekening");
    }
    if (transactionType != null && transactionType.equalsIgnoreCase("TRANSFER")) {
      return new CategoryResult("Transfer", "rule", 0.9, "Type TRANSFER");
    }
    if (isCryptoTransfer(description, merchant)) {
      return new CategoryResult("Crypto", "rule", 0.85, "Crypto exchange");
    }
    if (direction == TransactionDirection.IN) {
      return new CategoryResult("Inkomen", "rule", 0.9, "Inkomende transactie");
    }

    Optional<CategoryOverride> override = findOverride(userId, merchant, description, counterpartyIban);
    if (override.isPresent()) {
      return new CategoryResult(override.get().getCategory(), "override", 0.98, "Gebruikersregel");
    }

    String combined = buildCombined(description, merchant, counterpartyIban);
    TransactionCategorizer.RuleMatch match = ruleCategorizer.categorizeDetailed(combined, direction, transactionType);
    if (!"Overig".equalsIgnoreCase(match.category())) {
      double confidence = "Geen match".equalsIgnoreCase(match.reason()) ? 0.4 : 0.7;
      return new CategoryResult(match.category(), "rule", confidence, match.reason());
    }

    List<String> categories = allowedCategories(userId);
    if (allowAi) {
      String ai = openAiClient.classify(
          buildSystemPrompt(categories),
          buildUserPrompt(description, merchant, direction, transactionType, currency, amount, counterpartyIban),
          categories
      );
      if (ai != null) {
        return new CategoryResult(ai, "ai", 0.72, "AI classificatie op basis van omschrijving");
      }
    }

    double confidence = "Geen match".equalsIgnoreCase(match.reason()) ? 0.4 : 0.6;
    return new CategoryResult(match.category(), "rule", confidence, match.reason());
  }

  private String buildSystemPrompt(List<String> categories) {
    return "You categorize financial transactions. " +
        "Pick exactly one category from this list: " + String.join(", ", categories) + ". " +
        "Return only JSON like {\"category\":\"<one of the allowed categories>\"}.";
  }

  private String buildUserPrompt(String description,
                                 String merchant,
                                 TransactionDirection direction,
                                 String transactionType,
                                 String currency,
                                 String amount,
                                 String counterpartyIban) {
    return "Description: " + safe(description) + "\n" +
        "Merchant: " + safe(merchant) + "\n" +
        "Counterparty IBAN: " + safe(counterpartyIban) + "\n" +
        "Direction: " + (direction == null ? "" : direction.name()) + "\n" +
        "Type: " + safe(transactionType) + "\n" +
        "Amount: " + safe(amount) + "\n" +
        "Currency: " + safe(currency);
  }

  private String safe(String value) {
    return value == null ? "" : value;
  }

  private String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private String extractIban(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String upper = value.toUpperCase(Locale.ROOT);
    Matcher matcher = IBAN_PATTERN.matcher(upper);
    if (matcher.find()) {
      return matcher.group();
    }
    return null;
  }

  private Optional<CategoryOverride> findOverride(UUID userId,
                                                  String merchant,
                                                  String description,
                                                  String counterpartyIban) {
    if (userId == null) {
      return Optional.empty();
    }
    String iban = normalize(counterpartyIban);
    if (iban != null) {
      var override = overrideRepository.findFirstByUserIdAndMatchTypeAndMatchValue(
          userId,
          CategoryOverride.MatchType.IBAN,
          iban);
      if (override.isPresent()) {
        return override;
      }
    }
    String merchantKey = normalize(merchant);
    if (merchantKey != null) {
      var overrides = overrideRepository.findByUserIdAndMatchType(userId, CategoryOverride.MatchType.MERCHANT);
      var match = overrides.stream()
          .filter(rule -> matchesRule(merchantKey, rule))
          .max((a, b) -> compareRuleSpecificity(a, b));
      if (match.isPresent()) {
        return match;
      }
    }
    String descKey = normalize(description);
    if (descKey != null) {
      var overrides = overrideRepository.findByUserIdAndMatchType(userId, CategoryOverride.MatchType.DESCRIPTION);
      return overrides.stream()
          .filter(rule -> matchesRule(descKey, rule))
          .max((a, b) -> compareRuleSpecificity(a, b));
    }
    return Optional.empty();
  }

  private List<String> allowedCategories(UUID userId) {
    if (userId == null) {
      return CATEGORIES;
    }
    var items = transactionCategoryRepository.findByUserIdOrderByNameAsc(userId);
    if (items == null || items.isEmpty()) {
      return CATEGORIES;
    }
    return items.stream()
        .map(item -> item.getName())
        .filter(name -> name != null && !name.isBlank())
        .toList();
  }

  private String buildCombined(String description, String merchant, String counterpartyIban) {
    StringBuilder builder = new StringBuilder();
    if (merchant != null && !merchant.isBlank()) {
      builder.append(merchant).append(" ");
    }
    if (description != null && !description.isBlank()) {
      builder.append(description).append(" ");
    }
    if (counterpartyIban != null && !counterpartyIban.isBlank()) {
      builder.append(counterpartyIban);
    }
    return builder.toString().trim();
  }

  private boolean matchesRule(String value, CategoryOverride rule) {
    if (value == null || rule == null || rule.getMatchValue() == null) {
      return false;
    }
    CategoryOverride.MatchMode mode = rule.getMatchMode() == null
        ? CategoryOverride.MatchMode.CONTAINS
        : rule.getMatchMode();
    return mode == CategoryOverride.MatchMode.EXACT
        ? value.equals(rule.getMatchValue())
        : value.contains(rule.getMatchValue());
  }

  private int compareRuleSpecificity(CategoryOverride a, CategoryOverride b) {
    CategoryOverride.MatchMode modeA = a.getMatchMode() == null
        ? CategoryOverride.MatchMode.CONTAINS
        : a.getMatchMode();
    CategoryOverride.MatchMode modeB = b.getMatchMode() == null
        ? CategoryOverride.MatchMode.CONTAINS
        : b.getMatchMode();
    if (modeA != modeB) {
      return modeA == CategoryOverride.MatchMode.EXACT ? 1 : -1;
    }
    int lenA = a.getMatchValue() == null ? 0 : a.getMatchValue().length();
    int lenB = b.getMatchValue() == null ? 0 : b.getMatchValue().length();
    return Integer.compare(lenA, lenB);
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

  private boolean isCryptoTransfer(String description, String merchant) {
    String combined = buildCombined(description, merchant, null);
    String normalized = normalize(combined);
    if (normalized == null) {
      return false;
    }
    for (String keyword : CRYPTO_KEYWORDS) {
      if (normalized.contains(keyword)) {
        return true;
      }
    }
    return false;
  }

  private boolean isInternalTransfer(UUID userId, String counterpartyIban) {
    if (userId == null || counterpartyIban == null || counterpartyIban.isBlank()) {
      return false;
    }
    String normalized = normalize(counterpartyIban);
    if (normalized == null) {
      return false;
    }
    Set<String> identifiers = getAccountIdentifiers(userId);
    return identifiers.contains(normalized);
  }

  private Set<String> getAccountIdentifiers(UUID userId) {
    if (userId == null) {
      return Collections.emptySet();
    }
    Instant now = Instant.now();
    CachedAccounts cached = accountCache.get(userId);
    if (cached != null && cached.expiresAt().isAfter(now)) {
      return cached.identifiers();
    }
    List<FinancialAccount> accounts = accountRepository.findByUserId(userId);
    Set<String> identifiers = new HashSet<>();
    for (FinancialAccount account : accounts) {
      addIdentifier(identifiers, account.getIban());
      addIdentifier(identifiers, account.getAccountNumber());
    }
    CachedAccounts updated = new CachedAccounts(now.plus(ACCOUNT_CACHE_TTL), identifiers);
    accountCache.put(userId, updated);
    return identifiers;
  }

  private void addIdentifier(Set<String> identifiers, String value) {
    String normalized = normalize(value);
    if (normalized != null) {
      identifiers.add(normalized);
    }
  }

  private record CachedAccounts(Instant expiresAt, Set<String> identifiers) {}

  public record CategoryResult(String category, String source, Double confidence, String reason) {}
}
