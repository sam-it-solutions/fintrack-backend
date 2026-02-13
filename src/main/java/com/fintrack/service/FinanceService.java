package com.fintrack.service;

import com.fintrack.dto.AccountResponse;
import com.fintrack.dto.AccountShareRequest;
import com.fintrack.dto.CreateAccountRequest;
import com.fintrack.dto.CreateTransactionRequest;
import com.fintrack.dto.CurrencySummary;
import com.fintrack.dto.RecategorizeResponse;
import com.fintrack.dto.RecurringPaymentResponse;
import com.fintrack.dto.SpendingCategorySummary;
import com.fintrack.dto.SummaryResponse;
import com.fintrack.dto.TransactionResponse;
import com.fintrack.dto.UpdateAccountRequest;
import com.fintrack.dto.UpdateTransactionCategoryRequest;
import com.fintrack.model.AccountTransaction;
import com.fintrack.model.AccountType;
import com.fintrack.model.CategoryOverride;
import com.fintrack.model.FinancialAccount;
import com.fintrack.model.TransactionDirection;
import com.fintrack.provider.coingecko.CoinGeckoClient;
import com.fintrack.repository.HouseholdMemberRepository;
import com.fintrack.repository.AccountTransactionRepository;
import com.fintrack.repository.CategoryOverrideRepository;
import com.fintrack.repository.FinancialAccountRepository;
import com.fintrack.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FinanceService {
  private static final int MAX_AI_REQUESTS_PER_RELABEL_RUN = 30;

  private final FinancialAccountRepository accountRepository;
  private final AccountTransactionRepository transactionRepository;
  private final UserRepository userRepository;
  private final HouseholdMemberRepository householdMemberRepository;
  private final CategoryService categoryService;
  private final CategoryOverrideRepository overrideRepository;
  private final CoinGeckoClient coinGeckoClient;

  public FinanceService(FinancialAccountRepository accountRepository,
                        AccountTransactionRepository transactionRepository,
                        UserRepository userRepository,
                        HouseholdMemberRepository householdMemberRepository,
                        CategoryService categoryService,
                        CategoryOverrideRepository overrideRepository,
                        CoinGeckoClient coinGeckoClient) {
    this.accountRepository = accountRepository;
    this.transactionRepository = transactionRepository;
    this.userRepository = userRepository;
    this.householdMemberRepository = householdMemberRepository;
    this.categoryService = categoryService;
    this.overrideRepository = overrideRepository;
    this.coinGeckoClient = coinGeckoClient;
  }

  public AccountResponse createAccount(UUID userId, CreateAccountRequest request) {
    FinancialAccount account = new FinancialAccount();
    account.setUser(userRepository.findById(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));
    account.setType(request.getType());
    account.setProvider(request.getProvider());
    account.setName(request.getName());
    if (request.getLabel() != null && !request.getLabel().isBlank()) {
      account.setLabel(request.getLabel().trim());
    }
    account.setCurrency(request.getCurrency());
    account.setExternalId(request.getExternalId());
    account.setIban(request.getIban());
    account.setAccountNumber(request.getAccountNumber());
    account.setOpeningBalance(request.getOpeningBalance());
    if (account.getCurrentBalance() == null && request.getOpeningBalance() != null) {
      account.setCurrentBalance(request.getOpeningBalance());
      account.setCurrentFiatValue(request.getOpeningBalance());
      account.setFiatCurrency(request.getCurrency());
    }
    FinancialAccount saved = accountRepository.save(account);
    return toAccountResponse(saved);
  }

  public List<AccountResponse> listAccounts(UUID userId) {
    syncManualAccounts(userId);
    List<UUID> householdIds = householdIdsForUser(userId);
    List<FinancialAccount> accounts = householdIds.isEmpty()
        ? accountRepository.findActiveByUserId(userId)
        : accountRepository.findActiveByUserIdOrHouseholdIdIn(userId, householdIds);
    Map<String, BigDecimal> cryptoChanges = buildCryptoChangeMap(accounts);
    return accounts.stream()
        .map(account -> toAccountResponse(account, cryptoChanges))
        .toList();
  }

  public List<TransactionResponse> listTransactions(UUID userId, LocalDate from, LocalDate to) {
    syncManualAccounts(userId);
    List<UUID> householdIds = householdIdsForUser(userId);
    List<AccountTransaction> txs = householdIds.isEmpty()
        ? transactionRepository.findUserTransactionsInRange(userId, from, to)
        : transactionRepository.findUserAndHouseholdTransactionsInRange(userId, householdIds, from, to);
    return txs.stream().map(this::toTransactionResponse).toList();
  }

  public TransactionResponse createTransaction(UUID userId, CreateTransactionRequest request) {
    FinancialAccount account = accountRepository.findById(request.getAccountId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    if (!canAccessAccount(userId, account)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account not owned by user");
    }

    AccountTransaction tx = new AccountTransaction();
    tx.setAccount(account);
    tx.setAmount(request.getAmount());
    tx.setCurrency(request.getCurrency());
    tx.setDirection(request.getDirection());
    tx.setDescription(request.getDescription());
    tx.setBookingDate(request.getBookingDate());
    tx.setValueDate(request.getValueDate());
    if (request.getCategory() != null && !request.getCategory().isBlank()) {
      tx.setCategory(request.getCategory());
      tx.setCategorySource("manual");
      tx.setCategoryConfidence(java.math.BigDecimal.valueOf(1.0));
      tx.setCategoryReason("Handmatig ingesteld");
    } else {
      CategoryService.CategoryResult categoryResult = categoryService.categorizeDetailed(
          userId,
          request.getDescription(),
          null,
          request.getDirection(),
          null,
          account.getType(),
          request.getCurrency(),
          request.getAmount() == null ? null : request.getAmount().toPlainString(),
          null);
      tx.setCategory(categoryResult.category());
      tx.setCategorySource(categoryResult.source());
      if (categoryResult.confidence() != null) {
        tx.setCategoryConfidence(java.math.BigDecimal.valueOf(categoryResult.confidence()));
      }
      tx.setCategoryReason(categoryResult.reason());
    }
    AccountTransaction saved = transactionRepository.save(tx);
    return toTransactionResponse(saved);
  }

  public TransactionResponse updateTransactionCategory(UUID userId, UUID transactionId, UpdateTransactionCategoryRequest request) {
    AccountTransaction tx = transactionRepository.findById(transactionId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
    if (!canAccessAccount(userId, tx.getAccount())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Transaction not owned by user");
    }
    String category = request.getCategory() == null ? null : request.getCategory().trim();
    if (category == null || category.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category is required");
    }
    tx.setCategory(category);
    if (request.isApplyToFuture()) {
      CategoryOverride.MatchType matchType = resolveMatchType(tx);
      String matchValue = resolveMatchValue(tx, matchType);
      if (matchType != null && matchValue != null) {
        CategoryOverride override = overrideRepository
            .findFirstByUserIdAndMatchTypeAndMatchValue(userId, matchType, matchValue)
            .orElseGet(CategoryOverride::new);
        CategoryOverride.MatchMode matchMode = resolveMatchMode(matchType);
        if (override.getId() == null) {
          override.setUser(userRepository.findById(userId)
              .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));
          override.setMatchType(matchType);
          override.setMatchValue(matchValue);
          override.setMatchMode(matchMode);
          override.setCategory(category);
          override.setCreatedAt(Instant.now());
        } else {
          override.setCategory(category);
          if (override.getMatchMode() == null) {
            override.setMatchMode(matchMode);
          }
        }
        override.touch();
        overrideRepository.save(override);
        tx.setCategorySource("override");
        tx.setCategoryReason("Gebruikersregel (" + matchType.name() + ")");
        tx.setCategoryConfidence(java.math.BigDecimal.valueOf(1.0));
      } else {
        tx.setCategorySource("manual");
        tx.setCategoryReason("Handmatige aanpassing");
        tx.setCategoryConfidence(java.math.BigDecimal.valueOf(1.0));
      }
    } else {
      tx.setCategorySource("manual");
      tx.setCategoryReason("Handmatige aanpassing");
      tx.setCategoryConfidence(java.math.BigDecimal.valueOf(1.0));
    }
    AccountTransaction saved = transactionRepository.save(tx);
    return toTransactionResponse(saved);
  }

  public SummaryResponse getSummary(UUID userId) {
    syncManualAccounts(userId);
    List<UUID> householdIds = householdIdsForUser(userId);
    List<FinancialAccount> accounts = householdIds.isEmpty()
        ? accountRepository.findActiveByUserId(userId)
        : accountRepository.findActiveByUserIdOrHouseholdIdIn(userId, householdIds);
    Map<String, BigDecimal> balanceByCurrency = accounts.stream()
        .collect(Collectors.groupingBy(FinancialAccount::getCurrency,
            Collectors.mapping(a -> a.getCurrentBalance() == null ? BigDecimal.ZERO : a.getCurrentBalance(),
                Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));

    YearMonth month = YearMonth.now();
    LocalDate from = month.atDay(1);
    LocalDate to = month.atEndOfMonth();
    List<AccountTransaction> txs = householdIds.isEmpty()
        ? transactionRepository.findUserTransactionsInRangeByType(userId, AccountType.BANK, from, to)
        : transactionRepository.findUserAndHouseholdTransactionsInRangeByType(userId, householdIds, AccountType.BANK, from, to);

    Map<String, List<AccountTransaction>> txByCurrency = txs.stream()
        .collect(Collectors.groupingBy(AccountTransaction::getCurrency));

    List<CurrencySummary> summaries = new ArrayList<>();
    for (Map.Entry<String, BigDecimal> entry : balanceByCurrency.entrySet()) {
      String currency = entry.getKey();
      List<AccountTransaction> currencyTxs = txByCurrency.getOrDefault(currency, List.of());

      BigDecimal income = currencyTxs.stream()
          .filter(t -> t.getDirection() == com.fintrack.model.TransactionDirection.IN)
          .filter(t -> !isExcludedFromCashflow(t))
          .map(AccountTransaction::getAmount)
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      BigDecimal expense = currencyTxs.stream()
          .filter(t -> t.getDirection() == com.fintrack.model.TransactionDirection.OUT)
          .filter(t -> !isExcludedFromCashflow(t))
          .map(AccountTransaction::getAmount)
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      summaries.add(new CurrencySummary(currency, entry.getValue(), income, expense));
    }

    return new SummaryResponse(summaries);
  }

  private boolean isExcludedFromCashflow(AccountTransaction tx) {
    if (tx == null || tx.getCategory() == null) {
      return false;
    }
    String category = tx.getCategory();
    return "Transfer".equalsIgnoreCase(category) || "Crypto".equalsIgnoreCase(category);
  }

  public List<SpendingCategorySummary> getSpendingByCategory(UUID userId, YearMonth month) {
    syncManualAccounts(userId);
    List<UUID> householdIds = householdIdsForUser(userId);
    LocalDate from = month.atDay(1);
    LocalDate to = month.atEndOfMonth();
    List<Object[]> rows = householdIds.isEmpty()
        ? transactionRepository.sumByCategoryForUser(userId, AccountType.BANK, from, to)
        : transactionRepository.sumByCategoryForUserAndHouseholds(userId, householdIds, AccountType.BANK, from, to);
    if (rows.isEmpty()) {
      return List.of();
    }
    return rows.stream()
        .map(row -> new SpendingCategorySummary((String) row[0], (String) row[1], (BigDecimal) row[2]))
        .toList();
  }

  public List<RecurringPaymentResponse> getRecurringPayments(UUID userId, int months) {
    int safeMonths = Math.max(1, Math.min(24, months));
    YearMonth now = YearMonth.now();
    LocalDate from = now.minusMonths(safeMonths - 1L).atDay(1);
    LocalDate to = now.atEndOfMonth();

    List<UUID> householdIds = householdIdsForUser(userId);
    List<AccountTransaction> txs = householdIds.isEmpty()
        ? transactionRepository.findUserTransactionsInRangeByType(userId, AccountType.BANK, from, to)
        : transactionRepository.findUserAndHouseholdTransactionsInRangeByType(userId, householdIds, AccountType.BANK, from, to);

    Map<String, List<AccountTransaction>> grouped = new HashMap<>();
    for (AccountTransaction tx : txs) {
      if (tx.getDirection() != TransactionDirection.OUT) {
        continue;
      }
      if ("Transfer".equalsIgnoreCase(tx.getCategory())) {
        continue;
      }
      String key = recurringKey(tx);
      if (key == null) {
        continue;
      }
      grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
    }

    List<RecurringPaymentResponse> results = new ArrayList<>();
    for (List<AccountTransaction> items : grouped.values()) {
      Map<YearMonth, List<BigDecimal>> perMonth = new HashMap<>();
      LocalDate lastDate = null;
      for (AccountTransaction tx : items) {
        LocalDate date = resolveDate(tx);
        if (date == null) {
          continue;
        }
        YearMonth ym = YearMonth.from(date);
        perMonth.computeIfAbsent(ym, ignored -> new ArrayList<>()).add(tx.getAmount().abs());
        if (lastDate == null || date.isAfter(lastDate)) {
          lastDate = date;
        }
      }
      int distinctMonths = perMonth.keySet().size();
      if (distinctMonths < 3) {
        continue;
      }
      List<BigDecimal> amounts = items.stream()
          .map(tx -> tx.getAmount().abs())
          .filter(Objects::nonNull)
          .toList();
      if (amounts.isEmpty()) {
        continue;
      }
      BigDecimal average = amounts.stream()
          .reduce(BigDecimal.ZERO, BigDecimal::add)
          .divide(BigDecimal.valueOf(amounts.size()), 4, java.math.RoundingMode.HALF_UP);
      BigDecimal max = amounts.stream().max(BigDecimal::compareTo).orElse(average);
      BigDecimal min = amounts.stream().min(BigDecimal::compareTo).orElse(average);
      if (!withinTolerance(min, max, average, new BigDecimal("0.25"))) {
        continue;
      }
      AccountTransaction sample = items.get(0);
      String name = sample.getMerchantName() != null && !sample.getMerchantName().isBlank()
          ? sample.getMerchantName()
          : (sample.getDescription() == null ? "Onbekend" : sample.getDescription());
      results.add(new RecurringPaymentResponse(
          name,
          average,
          sample.getCurrency(),
          items.size(),
          distinctMonths,
          lastDate
      ));
    }

    results.sort((a, b) -> b.getAverageAmount().compareTo(a.getAverageAmount()));
    return results;
  }

  public RecategorizeResponse recategorizeAll(UUID userId) {
    List<UUID> householdIds = householdIdsForUser(userId);
    List<AccountTransaction> txs = householdIds.isEmpty()
        ? transactionRepository.findUserTransactions(userId)
        : transactionRepository.findUserAndHouseholdTransactions(userId, householdIds);

    int updated = 0;
    int aiCount = 0;
    for (AccountTransaction tx : txs) {
      boolean allowAi = aiCount < MAX_AI_REQUESTS_PER_RELABEL_RUN;
      CategoryService.CategoryResult categoryResult = categoryService.categorizeDetailed(
          userId,
          tx.getDescription(),
          tx.getMerchantName(),
          tx.getDirection(),
          tx.getTransactionType(),
          tx.getAccount().getType(),
          tx.getCurrency(),
          tx.getAmount() == null ? null : tx.getAmount().toPlainString(),
          tx.getCounterpartyIban(),
          allowAi);
      if (categoryResult.category() != null) {
        if ("ai".equalsIgnoreCase(categoryResult.source())) {
          aiCount++;
        }
        // Keep existing explicit category when AI budget is exhausted and rule fallback has no match.
        if (!allowAi
            && "Overig".equalsIgnoreCase(categoryResult.category())
            && tx.getCategory() != null
            && !tx.getCategory().isBlank()) {
          continue;
        }
        boolean changed = !java.util.Objects.equals(tx.getCategory(), categoryResult.category())
            || !java.util.Objects.equals(tx.getCategorySource(), categoryResult.source())
            || !java.util.Objects.equals(tx.getCategoryReason(), categoryResult.reason())
            || !java.util.Objects.equals(tx.getCategoryConfidence(),
                categoryResult.confidence() == null ? null : java.math.BigDecimal.valueOf(categoryResult.confidence()));
        tx.setCategory(categoryResult.category());
        tx.setCategorySource(categoryResult.source());
        if (categoryResult.confidence() != null) {
          tx.setCategoryConfidence(java.math.BigDecimal.valueOf(categoryResult.confidence()));
        }
        tx.setCategoryReason(categoryResult.reason());
        if (changed) {
          updated++;
        }
      }
    }
    if (!txs.isEmpty()) {
      transactionRepository.saveAll(txs);
    }
    return new RecategorizeResponse(updated, txs.size(), aiCount);
  }

  public List<TransactionResponse> listAiTransactions(UUID userId, int limit) {
    int safeLimit = Math.max(1, Math.min(limit, 200));
    List<UUID> householdIds = householdIdsForUser(userId);
    List<AccountTransaction> txs = householdIds.isEmpty()
        ? transactionRepository.findUserAiTransactions(userId, PageRequest.of(0, safeLimit))
        : transactionRepository.findUserAndHouseholdAiTransactions(userId, householdIds, PageRequest.of(0, safeLimit));
    return txs.stream().map(this::toTransactionResponse).toList();
  }

  public void requestSync(UUID userId, UUID accountId) {
    FinancialAccount account = accountRepository.findById(accountId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    if (!canAccessAccount(userId, account)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account not owned by user");
    }
    account.setLastSyncedAt(java.time.Instant.now());
    accountRepository.save(account);
  }

  public AccountResponse shareAccount(UUID userId, UUID accountId, AccountShareRequest request) {
    FinancialAccount account = accountRepository.findById(accountId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    if (!account.getUser().getId().equals(userId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can share an account");
    }
    var membership = householdMemberRepository.findByUserIdAndHouseholdId(userId, request.getHouseholdId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a household member"));
    account.setHousehold(membership.getHousehold());
    accountRepository.save(account);
    return toAccountResponse(account);
  }

  public AccountResponse updateAccount(UUID userId, UUID accountId, UpdateAccountRequest request) {
    FinancialAccount account = accountRepository.findById(accountId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    if (!canAccessAccount(userId, account)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account not owned by user");
    }
    if (request != null) {
      String label = request.getLabel();
      if (label != null) {
        String trimmed = label.trim();
        account.setLabel(trimmed.isBlank() ? null : trimmed);
      }
    }
    accountRepository.save(account);
    return toAccountResponse(account);
  }

  public void deleteAccount(UUID userId, UUID accountId) {
    FinancialAccount account = accountRepository.findById(accountId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    if (!account.getUser().getId().equals(userId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can delete an account");
    }
    if (!isManualAccount(account)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only manual accounts can be deleted");
    }
    transactionRepository.deleteByAccountId(accountId);
    accountRepository.delete(account);
  }

  private AccountResponse toAccountResponse(FinancialAccount account) {
    return toAccountResponse(account, null);
  }

  private AccountResponse toAccountResponse(FinancialAccount account, Map<String, BigDecimal> cryptoChanges) {
    BigDecimal change = null;
    if (cryptoChanges != null && account.getType() == AccountType.CRYPTO && account.getCurrency() != null) {
      change = cryptoChanges.get(account.getCurrency().toUpperCase(Locale.ROOT));
    }
    return new AccountResponse(
        account.getId(),
        account.getConnection() == null ? null : account.getConnection().getId(),
        account.getType(),
        account.getProvider(),
        account.getName(),
        account.getLabel(),
        account.getCurrency(),
        account.getExternalId(),
        account.getIban(),
        account.getAccountNumber(),
        account.getCurrentBalance(),
        account.getOpeningBalance(),
        account.getCurrentFiatValue(),
        account.getFiatCurrency(),
        account.getLastSyncedAt(),
        change);
  }

  private Map<String, BigDecimal> buildCryptoChangeMap(List<FinancialAccount> accounts) {
    List<String> symbols = accounts.stream()
        .filter(account -> account.getType() == AccountType.CRYPTO)
        .map(FinancialAccount::getCurrency)
        .filter(Objects::nonNull)
        .toList();
    if (symbols.isEmpty()) {
      return Map.of();
    }
    return coinGeckoClient.getEurChangePctBySymbols(symbols);
  }

  private TransactionResponse toTransactionResponse(AccountTransaction tx) {
    return new TransactionResponse(
        tx.getId(),
        tx.getAccount().getId(),
        tx.getAmount(),
        tx.getCurrency(),
        tx.getDirection(),
        tx.getDescription(),
        tx.getBookingDate(),
        tx.getValueDate(),
        tx.getCategory(),
        tx.getCategorySource(),
        tx.getCategoryConfidence(),
        tx.getCategoryReason(),
        tx.getStatus(),
        tx.getTransactionType(),
        tx.getMerchantName(),
        tx.getCounterpartyIban());
  }

  private List<UUID> householdIdsForUser(UUID userId) {
    return householdMemberRepository.findByUserId(userId).stream()
        .map(m -> m.getHousehold().getId())
        .toList();
  }

  private boolean canAccessAccount(UUID userId, FinancialAccount account) {
    if (account.getUser().getId().equals(userId)) {
      return true;
    }
    if (account.getHousehold() == null) {
      return false;
    }
    return householdMemberRepository.findByUserIdAndHouseholdId(userId, account.getHousehold().getId()).isPresent();
  }

  private void syncManualAccounts(UUID userId) {
    ZoneId zoneId = ZoneId.systemDefault();
    List<UUID> householdIds = householdIdsForUser(userId);
    List<FinancialAccount> accounts = householdIds.isEmpty()
        ? accountRepository.findActiveByUserId(userId)
        : accountRepository.findActiveByUserIdOrHouseholdIdIn(userId, householdIds);
    List<FinancialAccount> manualAccounts = accounts.stream()
        .filter(this::isManualAccount)
        .toList();
    if (manualAccounts.isEmpty()) {
      return;
    }

    Map<String, FinancialAccount> manualByIban = new HashMap<>();
    for (FinancialAccount account : manualAccounts) {
      String iban = normalizeIban(account.getIban());
      if (iban != null) {
        manualByIban.put(iban, account);
      }
      String accountNumber = normalizeIban(account.getAccountNumber());
      if (accountNumber != null) {
        manualByIban.putIfAbsent(accountNumber, account);
      }
    }
    if (manualByIban.isEmpty()) {
      return;
    }

    List<AccountTransaction> sourceTxs = householdIds.isEmpty()
        ? transactionRepository.findUserTransactions(userId)
        : transactionRepository.findUserAndHouseholdTransactions(userId, householdIds);

    for (AccountTransaction tx : sourceTxs) {
      if (tx.getAccount() == null || isManualAccount(tx.getAccount())) {
        continue;
      }
      if (tx.getDirection() == null) {
        continue;
      }
      String iban = normalizeIban(firstNonBlank(
          tx.getCounterpartyIban(),
          extractIbanFromText(tx.getDescription()),
          extractIbanFromText(tx.getMerchantName())));
      if (iban == null) {
        continue;
      }
      FinancialAccount manualAccount = manualByIban.get(iban);
      if (manualAccount == null) {
        continue;
      }
      LocalDate txDate = resolveDate(tx);
      Instant lastSynced = manualAccount.getLastSyncedAt();
      if (lastSynced == null) {
        // First sync for manual accounts should not backfill history.
        if (txDate == null || txDate.isBefore(LocalDate.now(zoneId))) {
          continue;
        }
      } else if (txDate != null) {
        LocalDate cutoffDate = lastSynced.atZone(zoneId).toLocalDate();
        if (txDate.isBefore(cutoffDate)) {
          continue;
        }
      }
      String externalId = "manual:" + manualAccount.getId() + ":" + tx.getId();
      if (transactionRepository.findFirstByAccountIdAndExternalIdOrderByCreatedAtAsc(manualAccount.getId(), externalId).isPresent()) {
        continue;
      }
      AccountTransaction mirror = new AccountTransaction();
      mirror.setAccount(manualAccount);
      mirror.setAmount(tx.getAmount());
      mirror.setCurrency(tx.getCurrency());
      mirror.setDirection(tx.getDirection() == com.fintrack.model.TransactionDirection.OUT
          ? com.fintrack.model.TransactionDirection.IN
          : com.fintrack.model.TransactionDirection.OUT);
      mirror.setBookingDate(tx.getBookingDate());
      mirror.setValueDate(tx.getValueDate());
      mirror.setExternalId(externalId);
      mirror.setProviderTransactionId(tx.getProviderTransactionId());
      mirror.setStatus(tx.getStatus());
      mirror.setTransactionType("TRANSFER");
      mirror.setMerchantName(tx.getAccount().getName());
      mirror.setDescription("Transfer " + (tx.getDirection() == com.fintrack.model.TransactionDirection.OUT ? "van" : "naar")
          + " " + tx.getAccount().getName());
      mirror.setCategory("Transfer");
      mirror.setCategorySource("system");
      mirror.setCategoryConfidence(java.math.BigDecimal.valueOf(1.0));
      mirror.setCategoryReason("Afgeleid van overschrijving naar handmatige rekening");
      mirror.setCounterpartyIban(tx.getAccount().getIban());
      transactionRepository.save(mirror);
    }

    for (FinancialAccount manualAccount : manualAccounts) {
      List<AccountTransaction> manualTxs = transactionRepository.findByAccountId(manualAccount.getId());
      BigDecimal opening = manualAccount.getOpeningBalance() == null ? BigDecimal.ZERO : manualAccount.getOpeningBalance();
      BigDecimal incoming = manualTxs.stream()
          .filter(t -> t.getDirection() == com.fintrack.model.TransactionDirection.IN)
          .map(AccountTransaction::getAmount)
          .filter(Objects::nonNull)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal outgoing = manualTxs.stream()
          .filter(t -> t.getDirection() == com.fintrack.model.TransactionDirection.OUT)
          .map(AccountTransaction::getAmount)
          .filter(Objects::nonNull)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal balance = opening.add(incoming).subtract(outgoing);
      manualAccount.setCurrentBalance(balance);
      manualAccount.setCurrentFiatValue(balance);
      manualAccount.setFiatCurrency(manualAccount.getCurrency());
      manualAccount.setLastSyncedAt(Instant.now());
      accountRepository.save(manualAccount);
    }
  }

  private boolean isManualAccount(FinancialAccount account) {
    if (account == null) {
      return false;
    }
    if (account.getConnection() != null) {
      return false;
    }
    return "manual".equalsIgnoreCase(account.getProvider());
  }

  private static final Pattern IBAN_PATTERN = Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}");

  private static LocalDate resolveDate(AccountTransaction tx) {
    if (tx == null) {
      return null;
    }
    return tx.getBookingDate() != null ? tx.getBookingDate() : tx.getValueDate();
  }

  private static boolean withinTolerance(BigDecimal min, BigDecimal max, BigDecimal average, BigDecimal tolerance) {
    if (average == null || average.compareTo(BigDecimal.ZERO) <= 0) {
      return false;
    }
    BigDecimal upper = average.multiply(BigDecimal.ONE.add(tolerance));
    BigDecimal lower = average.multiply(BigDecimal.ONE.subtract(tolerance));
    return max.compareTo(upper) <= 0 && min.compareTo(lower) >= 0;
  }

  private static String recurringKey(AccountTransaction tx) {
    if (tx == null) {
      return null;
    }
    String base = tx.getMerchantName();
    if (base == null || base.isBlank()) {
      base = tx.getDescription();
    }
    if (base == null || base.isBlank()) {
      return null;
    }
    return base.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
  }

  private static String extractIbanFromText(String text) {
    if (text == null) {
      return null;
    }
    Matcher matcher = IBAN_PATTERN.matcher(text.replaceAll("\\s+", ""));
    if (matcher.find()) {
      return matcher.group(0);
    }
    return null;
  }

  private static String normalizeIban(String iban) {
    if (iban == null) {
      return null;
    }
    String cleaned = iban.replaceAll("\\s+", "").trim().toUpperCase();
    return cleaned.isBlank() ? null : cleaned;
  }

  private CategoryOverride.MatchType resolveMatchType(AccountTransaction tx) {
    if (tx == null) {
      return null;
    }
    if (tx.getCounterpartyIban() != null && !tx.getCounterpartyIban().isBlank()) {
      return CategoryOverride.MatchType.IBAN;
    }
    if (tx.getMerchantName() != null && !tx.getMerchantName().isBlank()) {
      return CategoryOverride.MatchType.MERCHANT;
    }
    if (tx.getDescription() != null && !tx.getDescription().isBlank()) {
      return CategoryOverride.MatchType.DESCRIPTION;
    }
    return null;
  }

  private CategoryOverride.MatchMode resolveMatchMode(CategoryOverride.MatchType matchType) {
    if (matchType == null) {
      return CategoryOverride.MatchMode.CONTAINS;
    }
    return matchType == CategoryOverride.MatchType.IBAN
        ? CategoryOverride.MatchMode.EXACT
        : CategoryOverride.MatchMode.CONTAINS;
  }

  private String resolveMatchValue(AccountTransaction tx, CategoryOverride.MatchType matchType) {
    if (tx == null || matchType == null) {
      return null;
    }
    return switch (matchType) {
      case IBAN -> normalizeOverrideValue(tx.getCounterpartyIban());
      case MERCHANT -> normalizeOverrideValue(tx.getMerchantName());
      case DESCRIPTION -> normalizeOverrideValue(tx.getDescription());
    };
  }

  private String normalizeOverrideValue(String value) {
    if (value == null) {
      return null;
    }
    String cleaned = value.toLowerCase(java.util.Locale.ROOT)
        .replaceAll("\\s+", " ")
        .replaceAll("[^a-z0-9]+", "")
        .trim();
    return cleaned.isBlank() ? null : cleaned;
  }

  private static String firstNonBlank(String... values) {
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
}
