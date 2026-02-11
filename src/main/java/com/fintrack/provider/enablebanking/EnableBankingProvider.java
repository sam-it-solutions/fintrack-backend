package com.fintrack.provider.enablebanking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintrack.config.AppProperties;
import com.fintrack.config.EnableBankingProperties;
import com.fintrack.dto.ProviderField;
import com.fintrack.dto.ProviderResponse;
import com.fintrack.model.AccountTransaction;
import com.fintrack.model.AccountType;
import com.fintrack.model.Connection;
import com.fintrack.model.ConnectionStatus;
import com.fintrack.model.ConnectionType;
import com.fintrack.model.FinancialAccount;
import com.fintrack.model.TransactionDirection;
import com.fintrack.provider.ConnectResult;
import com.fintrack.provider.ConnectionProvider;
import com.fintrack.provider.SyncResult;
import com.fintrack.repository.AccountTransactionRepository;
import com.fintrack.repository.FinancialAccountRepository;
import com.fintrack.service.CategoryService;
import com.fintrack.service.SyncProgressService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

@Component
public class EnableBankingProvider implements ConnectionProvider {
  private static final String PROVIDER_ID = "enablebanking";
  private static final Logger log = LoggerFactory.getLogger(EnableBankingProvider.class);
  private static final Pattern IBAN_PATTERN = Pattern.compile("\\b[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}\\b");

  private final EnableBankingClient client;
  private final EnableBankingProperties properties;
  private final AppProperties appProperties;
  private final FinancialAccountRepository accountRepository;
  private final AccountTransactionRepository transactionRepository;
  private final CategoryService categoryService;
  private final SyncProgressService syncProgressService;

  public EnableBankingProvider(EnableBankingClient client,
                               EnableBankingProperties properties,
                               AppProperties appProperties,
                               FinancialAccountRepository accountRepository,
                               AccountTransactionRepository transactionRepository,
                               CategoryService categoryService,
                               SyncProgressService syncProgressService) {
    this.client = client;
    this.properties = properties;
    this.appProperties = appProperties;
    this.accountRepository = accountRepository;
    this.transactionRepository = transactionRepository;
    this.categoryService = categoryService;
    this.syncProgressService = syncProgressService;
  }

  @Override
  public String getProviderId() {
    return PROVIDER_ID;
  }

  @Override
  public ProviderResponse getMetadata() {
    return new ProviderResponse(
        PROVIDER_ID,
        "Enable Banking",
        ConnectionType.BANK,
        true,
        List.of(
            new ProviderField("aspspName", "Bank name", true, false, "Belfius"),
            new ProviderField("aspspCountry", "Country", true, false, "BE"),
            new ProviderField("psuType", "PSU type", false, false, "personal"),
            new ProviderField("language", "Language", false, false, "en"),
            new ProviderField("redirectUrl", "Redirect URL", false, false, "http://localhost:8085/api/providers/enablebanking/callback"),
            new ProviderField("consentDays", "Consent days", false, false, "30")
        )
    );
  }

  @Override
  public ConnectResult initiate(Connection connection, Map<String, String> config) {
    String aspspName = requireValue(config.get("aspspName"), "aspspName");
    String aspspCountry = firstNonBlank(config.get("aspspCountry"), properties.defaultCountry(), "BE");
    String psuType = firstNonBlank(config.get("psuType"), properties.defaultPsuType(), "personal");
    String language = firstNonBlank(config.get("language"), properties.defaultLanguage(), "en");
    int consentDays = parseInt(firstNonBlank(config.get("consentDays"),
        properties.consentDays() == null ? null : properties.consentDays().toString(),
        "30"), 30);

    String redirectUrl = firstNonBlank(config.get("redirectUrl"), properties.redirectUrl());
    if (redirectUrl == null || redirectUrl.isBlank()) {
      String backendUrl = appProperties.backendUrl() == null || appProperties.backendUrl().isBlank()
          ? "http://localhost:8085"
          : appProperties.backendUrl();
      redirectUrl = backendUrl + "/api/providers/enablebanking/callback";
    }

    String state = connection.getId().toString();
    String validUntil = client.formatValidUntil(consentDays);

    EnableBankingClient.AuthorizationResponse response = client.startAuthorization(
        aspspName,
        aspspCountry,
        redirectUrl,
        state,
        psuType,
        language,
        validUntil
    );
    if (response == null || response.url() == null || response.url().isBlank()) {
      throw new IllegalStateException("Enable Banking authorization failed");
    }
    return new ConnectResult(response.url(), response.authorizationId(), ConnectionStatus.PENDING);
  }

  @Override
  public SyncResult sync(Connection connection, Map<String, String> config) {
    String sessionId = config.get("sessionId");
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalStateException("Missing Enable Banking session. Complete the consent flow first.");
    }

    int accountsUpdated = 0;
    int transactionsImported = 0;

    syncProgressService.update(connection, "Sessie ophalen", 10);
    JsonNode sessionResponse = client.getSession(sessionId);
    syncProgressService.update(connection, "Accounts ophalen", 20);
    List<String> accountIds = new ArrayList<>(extractAccountIds(sessionResponse));
    List<String> configured = parseAccountIds(config.get("accountIds"));
    for (String configuredId : configured) {
      if (!accountIds.contains(configuredId)) {
        accountIds.add(configuredId);
      }
    }
    if (accountIds.isEmpty()) {
      throw new IllegalStateException("Enable Banking returned no account ids. Reconnect the bank to refresh consent.");
    }

    int totalAccounts = accountIds.size();
    int accountIndex = 0;
    for (String accountId : accountIds) {
      accountIndex++;
      int progress = 30 + (int) Math.round((accountIndex / (double) Math.max(totalAccounts, 1)) * 60);
      syncProgressService.update(
          connection,
          "Account " + accountIndex + " van " + totalAccounts + " synchroniseren",
          progress);
      JsonNode details = client.getAccountDetails(accountId);
      String name = firstNonBlank(text(details, "name"), text(details, "product"), text(details, "cash_account_type"));
      String currency = firstNonBlank(text(details, "currency"), text(details, "account.currency"), "EUR");
      String iban = firstNonBlank(text(details, "iban"), text(details, "account_id.iban"), text(details, "details.iban"));
      String accountNumber = firstNonBlank(text(details, "account_number"), text(details, "details.account_number"));

      JsonNode balances = client.getBalances(accountId);
      BigDecimal balance = extractBalance(balances);

      FinancialAccount account = accountRepository
          .findByConnectionIdAndExternalId(connection.getId(), accountId)
          .orElseGet(FinancialAccount::new);
      if (account.getId() == null) {
        account.setUser(connection.getUser());
        account.setConnection(connection);
        account.setType(AccountType.BANK);
        account.setProvider("Enable Banking");
        account.setExternalId(accountId);
      }
      account.setName(name == null || name.isBlank() ? "Bank account" : name);
      account.setCurrency(currency);
      account.setIban(iban);
      account.setAccountNumber(accountNumber);
      account.setCurrentBalance(balance);
      if (balance != null) {
        account.setCurrentFiatValue(balance);
        account.setFiatCurrency(currency);
      }
      account.setLastSyncedAt(Instant.now());
      accountRepository.save(account);
      accountsUpdated++;

      String continuationKey = null;
      boolean paginationSupported = true;
      do {
        String safeContinuation = normalizeContinuationKey(continuationKey);
        JsonNode txResponse;
        try {
          txResponse = client.getTransactions(accountId, safeContinuation);
        } catch (HttpClientErrorException.UnprocessableEntity ex) {
          String body = ex.getResponseBodyAsString();
          if (body != null && body.contains("continuation_key")) {
            paginationSupported = false;
            txResponse = client.getTransactions(accountId, null);
          } else {
            throw ex;
          }
        }
        logDebug("transactions response accountId=" + accountId, txResponse);
        Iterable<JsonNode> txItems = extractArray(txResponse, "transactions", "items");
        if (txItems == null) {
          JsonNode txObject = txResponse == null ? null : txResponse.path("transactions");
          if (txObject != null && txObject.isObject()) {
            List<JsonNode> merged = new ArrayList<>();
            JsonNode booked = txObject.path("booked");
            if (booked != null && booked.isArray()) {
              booked.forEach(merged::add);
            }
            JsonNode pending = txObject.path("pending");
            if (pending != null && pending.isArray()) {
              pending.forEach(merged::add);
            }
            if (!merged.isEmpty()) {
              txItems = merged;
            }
          }
        }
        if (paginationSupported) {
          continuationKey = normalizeContinuationKey(firstNonBlank(
              textValue(txResponse, "continuation_key"),
              textValue(txResponse, "continuationKey")));
        } else {
          continuationKey = null;
        }
        if (txItems == null) {
          break;
        }
        for (JsonNode txNode : txItems) {
          String externalId = firstNonBlank(
              text(txNode, "transaction_id"),
              text(txNode, "transactionId"),
              text(txNode, "entry_reference"),
              text(txNode, "internal_transaction_id"));
          if (externalId == null) {
            externalId = UUID.nameUUIDFromBytes(txNode.toString().getBytes(StandardCharsets.UTF_8)).toString();
          }

          BigDecimal signedAmount = firstAmount(
              txNode,
              "transaction_amount.amount",
              "amount.amount",
              "amount",
              "transactionAmount.amount");
          if (signedAmount == null) {
            continue;
          }
          TransactionDirection direction = resolveDirection(txNode, signedAmount, iban, accountNumber);
          BigDecimal absoluteAmount = signedAmount.abs();
          String txCurrency = firstNonBlank(
              text(txNode, "transaction_amount.currency"),
              text(txNode, "amount.currency"),
              text(txNode, "currency"),
              currency);
          String remittance = firstNonBlank(
              text(txNode, "remittance_information_unstructured"),
              text(txNode, "remittance_information_unstructured_array"),
              joinArray(txNode.path("remittance_information")));
          String creditorName = firstNonBlank(text(txNode, "creditor_name"), text(txNode, "creditor.name"));
          String debtorName = firstNonBlank(text(txNode, "debtor_name"), text(txNode, "debtor.name"));
          String counterparty = resolveCounterparty(direction, creditorName, debtorName);
          String description = firstNonBlank(counterparty, remittance);
          LocalDate bookingDate = parseDate(firstNonBlank(
              text(txNode, "booking_date"),
              text(txNode, "transaction_date"),
              text(txNode, "date"),
              text(txNode, "value_date")));
          LocalDate valueDate = parseDate(firstNonBlank(text(txNode, "value_date"), text(txNode, "transaction_date")));
          String txType = firstNonBlank(
              text(txNode, "transaction_type"),
              text(txNode, "proprietary_bank_transaction_code"),
              text(txNode, "bank_transaction_code.code"),
              text(txNode, "bank_transaction_code.sub_code"));
          String merchantName = counterparty;
          String creditorIban = firstNonBlank(
              text(txNode, "creditor_account.iban"),
              text(txNode, "creditorAccount.iban"),
              text(txNode, "creditor_account.other.identification"),
              text(txNode, "creditorAccount.other.identification"));
          String debtorIban = firstNonBlank(
              text(txNode, "debtor_account.iban"),
              text(txNode, "debtorAccount.iban"),
              text(txNode, "debtor_account.other.identification"),
              text(txNode, "debtorAccount.other.identification"));
          String counterpartyIban = direction == TransactionDirection.OUT
              ? firstNonBlank(creditorIban, debtorIban)
              : firstNonBlank(debtorIban, creditorIban);
          if (counterpartyIban == null || counterpartyIban.isBlank()) {
            counterpartyIban = firstNonBlank(extractIban(remittance), extractIban(description));
          }

          var existingOpt = transactionRepository.findFirstByAccountIdAndExternalIdOrderByCreatedAtAsc(account.getId(), externalId);
          if (existingOpt.isPresent()) {
            AccountTransaction existing = existingOpt.get();
            boolean changed = false;
            if (existing.getBookingDate() == null && bookingDate != null) {
              existing.setBookingDate(bookingDate);
              changed = true;
            }
            if (existing.getValueDate() == null && valueDate != null) {
              existing.setValueDate(valueDate);
              changed = true;
            }
            if ((existing.getDescription() == null || existing.getDescription().isBlank()) && description != null) {
              existing.setDescription(description);
              changed = true;
            }
            if ((existing.getCounterpartyIban() == null || existing.getCounterpartyIban().isBlank()) && counterpartyIban != null) {
              existing.setCounterpartyIban(counterpartyIban);
              changed = true;
            }
            if ((existing.getMerchantName() == null || existing.getMerchantName().isBlank()) && merchantName != null) {
              existing.setMerchantName(merchantName);
              changed = true;
            }
            if (existing.getTransactionType() == null && txType != null) {
              existing.setTransactionType(txType);
              changed = true;
            }
            if (existing.getCurrency() == null && txCurrency != null) {
              existing.setCurrency(txCurrency);
              changed = true;
            }
            if (existing.getAmount() == null && absoluteAmount != null) {
              existing.setAmount(absoluteAmount);
              changed = true;
            }
            if (existing.getDirection() == null && direction != null) {
              existing.setDirection(direction);
              changed = true;
            } else if (existing.getDirection() != null && direction != null && existing.getDirection() != direction) {
              existing.setDirection(direction);
              changed = true;
            }
            if (changed) {
              transactionRepository.save(existing);
            }
            continue;
          }

          AccountTransaction tx = new AccountTransaction();
          tx.setAccount(account);
          tx.setAmount(absoluteAmount);
          tx.setCurrency(txCurrency);
          tx.setDirection(direction);
          tx.setDescription(description);
          tx.setBookingDate(bookingDate);
          tx.setValueDate(valueDate);
          tx.setExternalId(externalId);
          tx.setProviderTransactionId(text(txNode, "transaction_id"));
          tx.setStatus(text(txNode, "status"));
          tx.setTransactionType(txType);
          tx.setMerchantName(merchantName);
          tx.setCounterpartyIban(counterpartyIban);

          CategoryService.CategoryResult categoryResult = categoryService.categorizeDetailed(
              connection.getUser().getId(),
              description,
              tx.getMerchantName(),
              direction,
              txType,
              account.getType(),
              txCurrency,
              absoluteAmount == null ? null : absoluteAmount.toPlainString(),
              counterpartyIban);
          tx.setCategory(categoryResult.category());
          tx.setCategorySource(categoryResult.source());
          if (categoryResult.confidence() != null) {
            tx.setCategoryConfidence(java.math.BigDecimal.valueOf(categoryResult.confidence()));
          }
          tx.setCategoryReason(categoryResult.reason());
          transactionRepository.save(tx);
          transactionsImported++;
        }
      } while (continuationKey != null && !continuationKey.isBlank());
    }

    syncProgressService.update(connection, "Afwerken", 95);
    return new SyncResult(accountsUpdated, transactionsImported, "OK");
  }

  private List<String> extractAccountIds(JsonNode sessionResponse) {
    var ids = new LinkedHashSet<String>();
    if (sessionResponse == null) {
      return List.of();
    }
    collectAccountIds(ids, sessionResponse.path("accounts"));
    collectAccountIds(ids, sessionResponse.path("accounts_data"));
    collectAccountIds(ids, sessionResponse.path("account_ids"));
    collectAccountIds(ids, sessionResponse.path("accountIds"));
    JsonNode accountIdNode = sessionResponse.path("account_id");
    String singleId = normalizeAccountId(accountIdNode);
    if (singleId != null) {
      ids.add(singleId);
    }
    return new ArrayList<>(ids);
  }

  private static String normalizeAccountId(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    if (node.isTextual() || node.isNumber()) {
      return node.asText();
    }
    if (node.isObject()) {
      String id = firstNonBlank(
          text(node, "uid"),
          text(node, "resource_id"),
          text(node, "resourceId"),
          text(node, "account_id.uid"),
          text(node, "account_id.id"),
          text(node, "id"),
          text(node, "account_id"),
          text(node, "accountId"),
          text(node, "iban"),
          text(node, "bban"),
          text(node, "account_number"));
      return id == null || id.isBlank() ? null : id;
    }
    return null;
  }

  private static void collectAccountIds(LinkedHashSet<String> ids, JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return;
    }
    if (node.isArray()) {
      for (JsonNode item : node) {
        String id = normalizeAccountId(item);
        if (id == null) {
          id = normalizeAccountId(item.path("account_id"));
        }
        if (id != null) {
          ids.add(id);
        }
      }
      return;
    }
    if (node.isObject()) {
      JsonNode nested = extractArray(node, "accounts", "items", "data", "results");
      if (nested != null) {
        collectAccountIds(ids, nested);
        return;
      }
      String id = normalizeAccountId(node);
      if (id != null) {
        ids.add(id);
      }
      return;
    }
    String id = normalizeAccountId(node);
    if (id != null) {
      ids.add(id);
    }
  }

  private static List<String> parseAccountIds(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    String[] parts = raw.split(",");
    List<String> results = new ArrayList<>();
    for (String part : parts) {
      String trimmed = part.trim();
      if (!trimmed.isBlank()) {
        results.add(trimmed);
      }
    }
    return results;
  }

  private static String requireValue(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing Enable Banking field: " + field);
    }
    return value;
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

  private static int parseInt(String value, int fallback) {
    if (value == null) {
      return fallback;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static JsonNode extractArray(JsonNode root, String... candidates) {
    if (root == null) {
      return null;
    }
    if (root.isArray()) {
      return root;
    }
    for (String key : candidates) {
      JsonNode node = root.path(key);
      if (node != null && node.isArray()) {
        return node;
      }
    }
    return null;
  }

  private static String text(JsonNode node, String path) {
    JsonNode current = node;
    for (String part : path.split("\\.")) {
      if (current == null) {
        return null;
      }
      current = current.path(part);
      if (current.isMissingNode() || current.isNull()) {
        return null;
      }
    }
    return current.isTextual() ? current.asText() : current.toString();
  }

  private static String textValue(JsonNode node, String path) {
    JsonNode current = node;
    for (String part : path.split("\\.")) {
      if (current == null) {
        return null;
      }
      current = current.path(part);
      if (current.isMissingNode() || current.isNull()) {
        return null;
      }
    }
    if (current.isTextual() || current.isNumber()) {
      return current.asText();
    }
    return null;
  }

  private static String normalizeContinuationKey(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isBlank()) {
      return null;
    }
    String lowered = trimmed.toLowerCase();
    if ("null".equals(lowered) || "undefined".equals(lowered)) {
      return null;
    }
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      return null;
    }
    return trimmed;
  }

  private static TransactionDirection resolveDirection(JsonNode txNode, BigDecimal amount, String accountIban, String accountNumber) {
    String indicator = firstNonBlank(
        text(txNode, "credit_debit_indicator"),
        text(txNode, "creditDebitIndicator"));
    if ("DBIT".equalsIgnoreCase(indicator) || "DEBIT".equalsIgnoreCase(indicator)) {
      return TransactionDirection.OUT;
    }
    if ("CRDT".equalsIgnoreCase(indicator) || "CREDIT".equalsIgnoreCase(indicator)) {
      return TransactionDirection.IN;
    }
    String creditorName = firstNonBlank(text(txNode, "creditor_name"), text(txNode, "creditor.name"));
    String debtorName = firstNonBlank(text(txNode, "debtor_name"), text(txNode, "debtor.name"));
    String creditorIban = firstNonBlank(
        text(txNode, "creditor_account.iban"),
        text(txNode, "creditorAccount.iban"),
        text(txNode, "creditor_account.other.identification"),
        text(txNode, "creditorAccount.other.identification"));
    String debtorIban = firstNonBlank(
        text(txNode, "debtor_account.iban"),
        text(txNode, "debtorAccount.iban"),
        text(txNode, "debtor_account.other.identification"),
        text(txNode, "debtorAccount.other.identification"));
    String normalizedAccountIban = normalizeIdentifier(accountIban);
    String normalizedAccountNumber = normalizeIdentifier(accountNumber);
    String normalizedCreditor = normalizeIdentifier(creditorIban);
    String normalizedDebtor = normalizeIdentifier(debtorIban);
    if (normalizedAccountIban != null || normalizedAccountNumber != null) {
      if (matchesAccount(normalizedAccountIban, normalizedAccountNumber, normalizedDebtor)) {
        return TransactionDirection.OUT;
      }
      if (matchesAccount(normalizedAccountIban, normalizedAccountNumber, normalizedCreditor)) {
        return TransactionDirection.IN;
      }
    }
    boolean hasCreditor = firstNonBlank(creditorName, creditorIban) != null;
    boolean hasDebtor = firstNonBlank(debtorName, debtorIban) != null;
    if (hasCreditor && !hasDebtor) {
      return TransactionDirection.OUT;
    }
    if (hasDebtor && !hasCreditor) {
      return TransactionDirection.IN;
    }
    return amount.signum() < 0 ? TransactionDirection.OUT : TransactionDirection.IN;
  }

  private static boolean matchesAccount(String iban, String accountNumber, String candidate) {
    if (candidate == null) {
      return false;
    }
    if (iban != null && iban.equals(candidate)) {
      return true;
    }
    return accountNumber != null && accountNumber.equals(candidate);
  }

  private static String normalizeIdentifier(String value) {
    if (value == null) {
      return null;
    }
    String cleaned = value.replaceAll("\\s+", "").trim().toUpperCase();
    return cleaned.isBlank() ? null : cleaned;
  }

  private static String extractIban(String value) {
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

  private static String resolveCounterparty(TransactionDirection direction, String creditorName, String debtorName) {
    if (direction == TransactionDirection.OUT) {
      return firstNonBlank(creditorName, debtorName);
    }
    if (direction == TransactionDirection.IN) {
      return firstNonBlank(debtorName, creditorName);
    }
    return firstNonBlank(creditorName, debtorName);
  }

  private static String joinArray(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
      return null;
    }
    StringBuilder builder = new StringBuilder();
    for (JsonNode item : node) {
      if (item.isTextual()) {
        if (builder.length() > 0) {
          builder.append(" | ");
        }
        builder.append(item.asText());
      }
    }
    return builder.length() == 0 ? null : builder.toString();
  }

  private static BigDecimal extractBalance(JsonNode balances) {
    if (balances == null) {
      return null;
    }
    JsonNode array = balances.isArray() ? balances : balances.path("balances");
    if (!array.isArray()) {
      return firstAmount(balances, "balance_amount.amount", "amount");
    }
    BigDecimal fallback = null;
    for (JsonNode balanceNode : array) {
      String type = firstNonBlank(text(balanceNode, "balance_type"), text(balanceNode, "balanceType"));
      BigDecimal amount = firstAmount(balanceNode, "balance_amount.amount", "amount");
      if (amount == null) {
        continue;
      }
      if ("CLAV".equalsIgnoreCase(type) || "CLBD".equalsIgnoreCase(type)) {
        return amount;
      }
      if (fallback == null) {
        fallback = amount;
      }
    }
    return fallback;
  }

  private static BigDecimal firstAmount(JsonNode node, String... paths) {
    if (node == null) {
      return null;
    }
    for (String path : paths) {
      JsonNode current = node;
      for (String part : path.split("\\.")) {
        if (current == null) {
          current = null;
          break;
        }
        current = current.path(part);
        if (current.isMissingNode() || current.isNull()) {
          current = null;
          break;
        }
      }
      if (current != null && !current.isNull()) {
        if (current.isNumber()) {
          return current.decimalValue();
        }
        if (current.isTextual()) {
          try {
            return new BigDecimal(current.asText());
          } catch (NumberFormatException ex) {
            return null;
          }
        }
      }
    }
    return null;
  }

  private static LocalDate parseDate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (Exception ex) {
      return null;
    }
  }

  private void logDebug(String label, JsonNode payload) {
    if (!Boolean.TRUE.equals(properties.debugLogResponses())) {
      return;
    }
    String body = payload == null ? "null" : payload.toString();
    if (body.length() > 4000) {
      body = body.substring(0, 4000) + "...";
    }
    log.info("Enable Banking debug {}: {}", label, body);
  }
}
