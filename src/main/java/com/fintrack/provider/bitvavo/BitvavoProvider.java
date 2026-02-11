package com.fintrack.provider.bitvavo;

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
import com.fintrack.provider.coingecko.CoinGeckoClient;
import com.fintrack.repository.AccountTransactionRepository;
import com.fintrack.repository.FinancialAccountRepository;
import com.fintrack.service.CategoryService;
import com.fintrack.service.SyncProgressService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class BitvavoProvider implements ConnectionProvider {
  private static final String PROVIDER_ID = "bitvavo";
  private static final Logger log = LoggerFactory.getLogger(BitvavoProvider.class);

  private final BitvavoClient client;
  private final FinancialAccountRepository accountRepository;
  private final AccountTransactionRepository transactionRepository;
  private final CategoryService categoryService;
  private final CoinGeckoClient coinGeckoClient;
  private final SyncProgressService syncProgressService;

  public BitvavoProvider(BitvavoClient client,
                         FinancialAccountRepository accountRepository,
                         AccountTransactionRepository transactionRepository,
                         CategoryService categoryService,
                         CoinGeckoClient coinGeckoClient,
                         SyncProgressService syncProgressService) {
    this.client = client;
    this.accountRepository = accountRepository;
    this.transactionRepository = transactionRepository;
    this.categoryService = categoryService;
    this.coinGeckoClient = coinGeckoClient;
    this.syncProgressService = syncProgressService;
  }

  @Override
  public String getProviderId() {
    return PROVIDER_ID;
  }

  @Override
  public ProviderResponse getMetadata() {
    List<ProviderField> fields = List.of(
        new ProviderField("apiKey", "API key", true, true, "bitvavo api key"),
        new ProviderField("apiSecret", "API secret", true, true, "bitvavo api secret"));
    return new ProviderResponse(PROVIDER_ID, "Bitvavo", ConnectionType.CRYPTO, false, fields);
  }

  @Override
  public ConnectResult initiate(Connection connection, Map<String, String> config) {
    return new ConnectResult(null, null, ConnectionStatus.ACTIVE);
  }

  @Override
  public SyncResult sync(Connection connection, Map<String, String> config) {
    String apiKey = config.get("apiKey");
    String apiSecret = config.get("apiSecret");
    if (apiKey == null || apiSecret == null) {
      throw new IllegalArgumentException("apiKey and apiSecret are required");
    }

    syncProgressService.update(connection, "Prijzen ophalen", 10);
    List<BitvavoClient.TickerPrice> prices = client.getTickerPrices();
    Map<String, BigDecimal> priceByMarket = prices == null ? Map.of() : prices.stream()
        .filter(p -> p.market() != null && p.price() != null)
        .collect(java.util.stream.Collectors.toMap(
            p -> p.market().toUpperCase(),
            p -> new BigDecimal(p.price()),
            (a, b) -> a));

    syncProgressService.update(connection, "Balances ophalen", 25);
    List<BitvavoClient.Balance> balances = client.getBalances(apiKey, apiSecret);
    log.info("Bitvavo sync balances: {}", balances == null ? 0 : balances.size());
    List<String> markets = balances == null ? List.of() : balances.stream()
        .map(balance -> balance.symbol() == null ? null : balance.symbol().toUpperCase() + "-EUR")
        .filter(market -> market != null && !market.startsWith("EUR-"))
        .distinct()
        .toList();
    List<BitvavoClient.Market> availableMarkets;
    try {
      availableMarkets = client.getMarkets();
    } catch (Exception ex) {
      log.warn("Bitvavo sync markets failed: {}", ex.getMessage());
      availableMarkets = List.of();
    }
    Set<String> validEurMarkets = availableMarkets == null ? Set.of() : availableMarkets.stream()
        .filter(market -> market.market() != null)
        .filter(market -> "EUR".equalsIgnoreCase(market.quote()))
        .filter(market -> market.status() == null || "trading".equalsIgnoreCase(market.status()))
        .map(market -> market.market().toUpperCase())
        .collect(Collectors.toSet());
    if (!validEurMarkets.isEmpty()) {
      int originalCount = markets.size();
      markets = markets.stream().filter(validEurMarkets::contains).toList();
      if (originalCount != markets.size()) {
        log.info("Bitvavo sync filtered invalid markets: {} -> {}", originalCount, markets.size());
      }
    }
    Map<String, BigDecimal> fallbackPrices = balances == null ? Map.of() : coinGeckoClient.getEurPricesBySymbols(
        balances.stream()
            .map(BitvavoClient.Balance::symbol)
            .toList());

    int accountsUpdated = 0;
    if (balances != null) {
      for (BitvavoClient.Balance balance : balances) {
        if (balance == null || balance.available() == null) {
          continue;
        }
        FinancialAccount account = accountRepository
            .findByConnectionIdAndExternalId(connection.getId(), balance.symbol())
            .orElseGet(FinancialAccount::new);
        if (account.getId() == null) {
          account.setUser(connection.getUser());
          account.setConnection(connection);
          account.setType(AccountType.CRYPTO);
          account.setProvider("Bitvavo");
          account.setExternalId(balance.symbol());
        }
        account.setName(balance.symbol());
        account.setCurrency(balance.symbol());
        account.setCurrentBalance(balance.available());
        BigDecimal fiatValue = toEurValue(balance.symbol(), balance.available(), priceByMarket, fallbackPrices);
        account.setCurrentFiatValue(fiatValue);
        account.setFiatCurrency(fiatValue == null ? null : "EUR");
        account.setLastSyncedAt(Instant.now());
        accountRepository.save(account);
        accountsUpdated++;
      }
    }

    syncProgressService.update(connection, "Trades ophalen", 70);
    int transactionsImported = 0;
    List<BitvavoClient.Transaction> transactions;
    try {
      transactions = client.getTransactions(apiKey, apiSecret, markets);
    } catch (Exception ex) {
      log.warn("Bitvavo sync transactions failed: {}", ex.getMessage());
      transactions = List.of();
    }
    log.info("Bitvavo sync transactions: {}", transactions == null ? 0 : transactions.size());
    if (transactions != null) {
      for (BitvavoClient.Transaction t : transactions) {
        if (t == null || t.id() == null) {
          continue;
        }
        String market = t.market() != null ? t.market() : t.symbol();
        String asset = market == null ? null : market.split("-")[0];
        FinancialAccount account = asset == null ? null : accountRepository
            .findByConnectionIdAndExternalId(connection.getId(), asset)
            .orElse(null);
        if (account == null) {
          continue;
        }
        var existing = transactionRepository.findFirstByAccountIdAndExternalIdOrderByCreatedAtAsc(account.getId(), t.id());
        if (existing.isPresent()) {
          AccountTransaction existingTx = existing.get();
          LocalDate bookingDate = resolveBookingDate(t);
          if (bookingDate != null && !bookingDate.equals(existingTx.getBookingDate())) {
            existingTx.setBookingDate(bookingDate);
            transactionRepository.save(existingTx);
          }
          continue;
        }
        BigDecimal amount = t.amount() == null ? BigDecimal.ZERO : t.amount().abs();
        TransactionDirection direction = "sell".equalsIgnoreCase(t.side()) ? TransactionDirection.IN : TransactionDirection.OUT;
        AccountTransaction tx = new AccountTransaction();
        tx.setAccount(account);
        tx.setAmount(amount);
        tx.setCurrency(account.getCurrency());
        tx.setDirection(direction);
        String description = "Bitvavo trade " + (asset == null ? (t.symbol() == null ? "crypto" : t.symbol()) : asset);
        tx.setDescription(description);
        tx.setBookingDate(resolveBookingDate(t));
        tx.setExternalId(t.id());
        tx.setProviderTransactionId(t.id());
        tx.setStatus("BOOKED");
        tx.setTransactionType("TRADE");
        tx.setMerchantName("Bitvavo");
        CategoryService.CategoryResult categoryResult = categoryService.categorizeDetailed(
            connection.getUser().getId(),
            description,
            tx.getMerchantName(),
            direction,
            "TRADE",
            account.getType(),
            account.getCurrency(),
            amount == null ? null : amount.toPlainString(),
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
    }

    syncProgressService.update(connection, "Afwerken", 95);
    return new SyncResult(accountsUpdated, transactionsImported, "OK");
  }

  private static LocalDate resolveBookingDate(BitvavoClient.Transaction t) {
    if (t == null) {
      return null;
    }
    Long timestamp = t.timestamp();
    if (timestamp == null) {
      return LocalDate.now(ZoneId.systemDefault());
    }
    long epochMillis = timestamp < 1_000_000_000_000L ? timestamp * 1000L : timestamp;
    return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate();
  }

  private static BigDecimal toEurValue(String asset,
                                       BigDecimal amount,
                                       Map<String, BigDecimal> priceByMarket,
                                       Map<String, BigDecimal> fallbackPrices) {
    if (asset == null || amount == null) {
      return null;
    }
    if ("EUR".equalsIgnoreCase(asset)) {
      return amount;
    }
    String symbol = asset.toUpperCase();
    BigDecimal direct = priceByMarket.get(symbol + "-EUR");
    if (direct != null) {
      return amount.multiply(direct);
    }
    BigDecimal usdtEur = priceByMarket.get("USDT-EUR");
    BigDecimal usdcEur = priceByMarket.get("USDC-EUR");
    if (isStable(symbol)) {
      if (direct != null) {
        return amount.multiply(direct);
      }
      if (usdtEur != null && "USDT".equals(symbol)) {
        return amount.multiply(usdtEur);
      }
      if (usdcEur != null && "USDC".equals(symbol)) {
        return amount.multiply(usdcEur);
      }
      return amount;
    }
    BigDecimal viaUsdt = priceByMarket.get(symbol + "-USDT");
    if (viaUsdt != null && usdtEur != null) {
      return amount.multiply(viaUsdt).multiply(usdtEur);
    }
    BigDecimal viaUsdc = priceByMarket.get(symbol + "-USDC");
    if (viaUsdc != null && usdcEur != null) {
      return amount.multiply(viaUsdc).multiply(usdcEur);
    }
    BigDecimal btcEur = priceByMarket.get("BTC-EUR");
    BigDecimal viaBtc = priceByMarket.get(symbol + "-BTC");
    if (viaBtc != null && btcEur != null) {
      return amount.multiply(viaBtc).multiply(btcEur);
    }
    if (fallbackPrices != null) {
      BigDecimal fallback = fallbackPrices.get(symbol);
      if (fallback != null) {
        return amount.multiply(fallback);
      }
    }
    return null;
  }

  private static boolean isStable(String symbol) {
    return "USDT".equals(symbol) || "USDC".equals(symbol) || "DAI".equals(symbol) || "EURT".equals(symbol);
  }
}
