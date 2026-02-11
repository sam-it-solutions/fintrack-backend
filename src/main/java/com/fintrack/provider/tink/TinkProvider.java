package com.fintrack.provider.tink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintrack.config.AppProperties;
import com.fintrack.config.TinkProperties;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TinkProvider implements ConnectionProvider {
  private static final String PROVIDER_ID = "tink";

  private final TinkClient client;
  private final TinkProperties properties;
  private final AppProperties appProperties;
  private final FinancialAccountRepository accountRepository;
  private final AccountTransactionRepository transactionRepository;
  private final CategoryService categoryService;
  private final SyncProgressService syncProgressService;

  public TinkProvider(TinkClient client,
                      TinkProperties properties,
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
        "Tink Open Banking",
        ConnectionType.BANK,
        true,
        List.of(
            new com.fintrack.dto.ProviderField("redirectUrl", "Redirect URL", false, false, "http://localhost:8085/api/providers/tink/callback"),
            new com.fintrack.dto.ProviderField("market", "Market", false, false, "BE"),
            new com.fintrack.dto.ProviderField("locale", "Locale", false, false, "en_US"),
            new com.fintrack.dto.ProviderField("scope", "Scope", false, false, "accounts:read,transactions:read"),
            new com.fintrack.dto.ProviderField("externalUserId", "External User ID", false, false, "Optional")
        )
    );
  }

  @Override
  public ConnectResult initiate(Connection connection, Map<String, String> config) {
    String template = properties.linkUrlTemplate();
    if (template == null || template.isBlank()) {
      throw new IllegalStateException("Missing Tink linkUrlTemplate configuration");
    }

    String clientId = requireValue(properties.clientId(), "clientId");
    String market = firstNonBlank(config.get("market"), properties.market(), "BE");
    String locale = firstNonBlank(config.get("locale"), properties.locale(), "en_US");
    String scope = firstNonBlank(config.get("scope"), properties.scope(), "accounts:read,transactions:read");
    String externalUserId = firstNonBlank(config.get("externalUserId"), connection.getId().toString());

    String redirectUrl = firstNonBlank(config.get("redirectUrl"), properties.redirectUrl());
    if (redirectUrl == null || redirectUrl.isBlank()) {
      String backendUrl = appProperties.backendUrl() == null || appProperties.backendUrl().isBlank()
          ? "http://localhost:8085"
          : appProperties.backendUrl();
      redirectUrl = backendUrl + "/api/providers/tink/callback";
    }

    String state = connection.getId().toString();
    String link = template
        .replace("{clientId}", encode(clientId))
        .replace("{redirectUrl}", encode(redirectUrl))
        .replace("{market}", encode(market))
        .replace("{locale}", encode(locale))
        .replace("{scope}", encode(scope))
        .replace("{state}", encode(state))
        .replace("{externalUserId}", encode(externalUserId));

    return new ConnectResult(link, externalUserId, ConnectionStatus.PENDING);
  }

  @Override
  public SyncResult sync(Connection connection, Map<String, String> config) {
    String accessToken = config.get("accessToken");
    if (accessToken == null || accessToken.isBlank()) {
      throw new IllegalStateException("Missing Tink access token. Complete the consent flow first.");
    }

    syncProgressService.update(connection, "Accounts ophalen", 15);
    int accountsUpdated = 0;
    int transactionsImported = 0;
    String nextAccountPage = null;
    do {
      JsonNode accountsResponse = client.listAccounts(accessToken, nextAccountPage);
      JsonNode accountArray = extractArray(accountsResponse, "accounts", "data", "items");
      nextAccountPage = firstNonBlank(text(accountsResponse, "nextPageToken"), text(accountsResponse, "next_page_token"));

      if (accountArray == null) {
        break;
      }
      for (JsonNode accountNode : accountArray) {
        String accountId = firstNonBlank(text(accountNode, "id"), text(accountNode, "accountId"));
        if (accountId == null) {
          continue;
        }
        String name = firstNonBlank(text(accountNode, "name"), text(accountNode, "displayName"), accountId);
        String currency = firstNonBlank(
            text(accountNode, "currency"),
            text(accountNode, "balances.available.currency"),
            text(accountNode, "balances.current.currency"),
            text(accountNode, "balances.booked.amount.currencyCode"),
            text(accountNode, "balance.currency"),
            "EUR");
        String iban = firstNonBlank(
            text(accountNode, "identifiers.iban.iban"),
            text(accountNode, "iban"));
        String accountNumber = firstNonBlank(
            text(accountNode, "identifiers.financialInstitution.accountNumber"),
            text(accountNode, "accountNumber"));

        BigDecimal balance = firstAmount(
            accountNode,
            "balances.available.amount.value",
            "balances.current.amount.value",
            "balances.booked.amount.value",
            "balances.available.amount",
            "balances.current.amount",
            "balances.booked.amount",
            "balance.amount.value",
            "balance.amount",
            "balance",
            "availableAmount",
            "currentAmount");
        if (balance == null) {
          balance = extractBalanceFromArray(accountNode.path("balances"));
        }

        FinancialAccount account = accountRepository
            .findByConnectionIdAndExternalId(connection.getId(), accountId)
            .orElseGet(FinancialAccount::new);
        if (account.getId() == null) {
          account.setUser(connection.getUser());
          account.setConnection(connection);
          account.setType(AccountType.BANK);
          account.setProvider("Tink");
          account.setExternalId(accountId);
        }
        account.setName(name);
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
        int progress = Math.min(90, 25 + accountsUpdated * 8);
        syncProgressService.update(connection, "Transacties ophalen", progress);
        String nextTxPage = null;
        do {
          JsonNode transactionsResponse = client.listTransactions(accessToken, accountId, nextTxPage);
          JsonNode txArray = extractTransactions(transactionsResponse);
          nextTxPage = firstNonBlank(text(transactionsResponse, "nextPageToken"), text(transactionsResponse, "next_page_token"));
          if (txArray == null) {
            break;
          }

          for (JsonNode txNode : txArray) {
            String txAccountId = firstNonBlank(text(txNode, "accountId"), text(txNode, "account.id"));
            if (txAccountId != null && !txAccountId.equals(accountId)) {
              continue;
            }
            String externalId = firstNonBlank(
                text(txNode, "id"),
                text(txNode, "identifiers.providerTransactionId"),
                text(txNode, "transactionId"),
                text(txNode, "internalTransactionId"),
                text(txNode, "entryReference"));
            if (externalId == null) {
              externalId = UUID.nameUUIDFromBytes(txNode.toString().getBytes(StandardCharsets.UTF_8)).toString();
            }
            if (transactionRepository.findFirstByAccountIdAndExternalIdOrderByCreatedAtAsc(account.getId(), externalId).isPresent()) {
              continue;
            }

            BigDecimal signedAmount = firstAmount(
                txNode,
                "amount.value",
                "amount",
                "transactionAmount.amount",
                "transactionAmount.amount.value",
                "amount.value.value");
            if (signedAmount == null) {
              continue;
            }
            TransactionDirection direction = signedAmount.signum() < 0 ? TransactionDirection.OUT : TransactionDirection.IN;
            BigDecimal absoluteAmount = signedAmount.abs();
            String txCurrency = firstNonBlank(
                text(txNode, "currency"),
                text(txNode, "amount.currency"),
                text(txNode, "amount.currencyCode"),
                text(txNode, "transactionAmount.currency"),
                currency);
            String description = firstNonBlank(
                text(txNode, "description"),
                text(txNode, "text"),
                text(txNode, "descriptions.display"),
                text(txNode, "descriptions.original"),
                text(txNode, "merchant.name"),
                text(txNode, "counterpart.name"));

            AccountTransaction tx = new AccountTransaction();
            tx.setAccount(account);
            tx.setAmount(absoluteAmount);
            tx.setCurrency(txCurrency);
            tx.setDirection(direction);
            tx.setDescription(description);
            tx.setBookingDate(parseDate(firstNonBlank(
                text(txNode, "date"),
                text(txNode, "bookingDate"),
                text(txNode, "dates.booked"))));
            tx.setValueDate(parseDate(text(txNode, "valueDate")));
            tx.setExternalId(externalId);
            tx.setProviderTransactionId(text(txNode, "identifiers.providerTransactionId"));
            tx.setStatus(firstNonBlank(text(txNode, "status"), text(txNode, "bookingStatus")));
            String txType = firstNonBlank(text(txNode, "types.type"), text(txNode, "type"));
            tx.setTransactionType(txType);
            tx.setMerchantName(text(txNode, "merchant.name"));
            CategoryService.CategoryResult categoryResult = categoryService.categorizeDetailed(
                connection.getUser().getId(),
                description,
                tx.getMerchantName(),
                direction,
                txType,
                account.getType(),
                txCurrency,
                absoluteAmount == null ? null : absoluteAmount.toPlainString(),
                null);
            tx.setCategory(categoryResult.category());
            tx.setCategorySource(categoryResult.source());
            if (categoryResult.confidence() != null) {
              tx.setCategoryConfidence(java.math.BigDecimal.valueOf(categoryResult.confidence()));
            }
            tx.setCategoryReason(categoryResult.reason());
            transactionRepository.save(tx);
            transactionsImported++;
          }
        } while (nextTxPage != null && !nextTxPage.isBlank());
      }
    } while (nextAccountPage != null && !nextAccountPage.isBlank());

    syncProgressService.update(connection, "Afwerken", 95);
    return new SyncResult(accountsUpdated, transactionsImported, "OK");
  }

  private static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private static String requireValue(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing Tink configuration: " + field);
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

  private static JsonNode extractTransactions(JsonNode root) {
    if (root == null) {
      return null;
    }
    if (root.isArray()) {
      return root;
    }
    JsonNode transactions = root.path("transactions");
    if (transactions != null) {
      if (transactions.isArray()) {
        return transactions;
      }
      JsonNode booked = transactions.path("booked");
      if (booked.isArray()) {
        return booked;
      }
    }
    JsonNode data = root.path("data");
    if (data.isArray()) {
      return data;
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

  private static BigDecimal firstAmount(JsonNode node, String... paths) {
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
      if (current == null) {
        continue;
      }
      BigDecimal parsed = parseAmountNode(current);
      if (parsed != null) {
        return parsed;
      }
    }
    return null;
  }

  private static BigDecimal parseAmountNode(JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isNumber()) {
      return node.decimalValue();
    }
    if (node.isTextual()) {
      try {
        return new BigDecimal(node.asText());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    if (node.isObject()) {
      JsonNode unscaled = node.get("unscaledValue");
      JsonNode scale = node.get("scale");
      BigDecimal unscaledValue = parseAmountNode(unscaled);
      Integer scaleValue = parseScale(scale);
      if (unscaledValue != null) {
        if (scaleValue != null) {
          return unscaledValue.movePointLeft(Math.abs(scaleValue));
        }
        return unscaledValue;
      }
      JsonNode value = node.get("value");
      if (value != null) {
        BigDecimal parsed = parseAmountNode(value);
        if (parsed != null && scaleValue != null) {
          return parsed.movePointLeft(Math.abs(scaleValue));
        }
        return parsed;
      }
      JsonNode amount = node.get("amount");
      if (amount != null) {
        return parseAmountNode(amount);
      }
    }
    return null;
  }

  private static Integer parseScale(JsonNode scale) {
    if (scale == null || scale.isNull()) {
      return null;
    }
    if (scale.canConvertToInt()) {
      return scale.asInt();
    }
    if (scale.isTextual()) {
      try {
        return Integer.parseInt(scale.asText());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static BigDecimal extractBalanceFromArray(JsonNode balancesNode) {
    if (balancesNode == null || !balancesNode.isArray()) {
      return null;
    }
    BigDecimal fallback = null;
    for (JsonNode entry : balancesNode) {
      String type = firstNonBlank(text(entry, "type"), text(entry, "balanceType"));
      BigDecimal amount = parseAmountNode(entry.get("amount"));
      if (amount == null) {
        amount = parseAmountNode(entry.get("balanceAmount"));
      }
      if (amount == null) {
        continue;
      }
      if ("available".equalsIgnoreCase(type) || "current".equalsIgnoreCase(type) || "closingBooked".equalsIgnoreCase(type)) {
        return amount;
      }
      fallback = amount;
    }
    return fallback;
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
}
