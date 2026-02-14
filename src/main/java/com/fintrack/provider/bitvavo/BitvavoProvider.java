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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    Map<String, InvestState> investmentStateBySymbol = new HashMap<>();
    List<BitvavoClient.Transaction> transactions;
    try {
      transactions = client.getTransactions(apiKey, apiSecret, markets);
    } catch (Exception ex) {
      log.warn("Bitvavo sync transactions failed: {}", ex.getMessage());
      transactions = List.of();
    }
    log.info("Bitvavo sync transactions: {}", transactions == null ? 0 : transactions.size());
    if (transactions != null) {
      transactions = transactions.stream()
          .filter(Objects::nonNull)
          .sorted(Comparator.comparingLong(t -> t.timestamp() == null ? Long.MAX_VALUE : t.timestamp()))
          .toList();
      for (BitvavoClient.Transaction t : transactions) {
        if (t == null) {
          continue;
        }
        String asset = extractAssetSymbol(t);
        registerInvestment(investmentStateBySymbol, asset, t, priceByMarket, fallbackPrices);
        String externalId = resolveExternalTransactionId(t);
        if (externalId == null) {
          continue;
        }
        FinancialAccount account = asset == null ? null : accountRepository
            .findByConnectionIdAndExternalId(connection.getId(), asset)
            .orElse(null);
        if (account == null) {
          continue;
        }
        String side = resolveSide(t);
        if (side == null) {
          continue;
        }
        var existing = transactionRepository.findFirstByAccountIdAndExternalIdOrderByCreatedAtAsc(account.getId(), externalId);
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
        TransactionDirection direction = "sell".equalsIgnoreCase(side) ? TransactionDirection.IN : TransactionDirection.OUT;
        AccountTransaction tx = new AccountTransaction();
        tx.setAccount(account);
        tx.setAmount(amount);
        tx.setCurrency(account.getCurrency());
        tx.setDirection(direction);
        String description = "Bitvavo trade " + (asset == null ? (t.symbol() == null ? "crypto" : t.symbol()) : asset);
        tx.setDescription(description);
        tx.setBookingDate(resolveBookingDate(t));
        tx.setExternalId(externalId);
        tx.setProviderTransactionId(externalId);
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

    if (balances != null) {
      for (BitvavoClient.Balance balance : balances) {
        if (balance == null || balance.symbol() == null) {
          continue;
        }
        String symbol = balance.symbol().toUpperCase();
        FinancialAccount account = accountRepository
            .findByConnectionIdAndExternalId(connection.getId(), balance.symbol())
            .orElse(null);
        if (account == null) {
          continue;
        }
        InvestState state = investmentStateBySymbol.get(symbol);
        BigDecimal invested = state == null ? BigDecimal.ZERO : state.costEur.max(BigDecimal.ZERO);
        account.setOpeningBalance(invested);
        accountRepository.save(account);
      }
    }

    syncProgressService.update(connection, "Afwerken", 95);
    return new SyncResult(accountsUpdated, transactionsImported, "OK");
  }

  private static void registerInvestment(Map<String, InvestState> investmentStateBySymbol,
                                         String asset,
                                         BitvavoClient.Transaction t,
                                         Map<String, BigDecimal> priceByMarket,
                                         Map<String, BigDecimal> fallbackPrices) {
    if (investmentStateBySymbol == null || asset == null || t == null || t.amount() == null) {
      return;
    }
    String side = resolveSide(t);
    if (side == null) {
      return;
    }
    String symbol = asset.toUpperCase(Locale.ROOT);
    String quoteCurrency = extractQuoteCurrency(t);
    BigDecimal tradeValueEur = resolveTradeValueEur(t, quoteCurrency, priceByMarket, fallbackPrices);
    if (tradeValueEur == null) {
      return;
    }
    BigDecimal quantity = t.amount().abs();
    if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    BigDecimal feeEur = resolveFeeEur(t, quoteCurrency, priceByMarket, fallbackPrices);
    BigDecimal feeInBaseUnits = "buy".equalsIgnoreCase(side) || "sell".equalsIgnoreCase(side)
        ? resolveFeeInBaseUnits(t, symbol)
        : BigDecimal.ZERO;

    InvestState state = investmentStateBySymbol.computeIfAbsent(symbol, key -> new InvestState());
    if ("buy".equalsIgnoreCase(side)) {
      BigDecimal netUnits = quantity.subtract(feeInBaseUnits).max(BigDecimal.ZERO);
      state.units = state.units.add(netUnits);
      state.costEur = state.costEur.add(tradeValueEur).add(feeEur);
      return;
    }
    if ("sell".equalsIgnoreCase(side)) {
      BigDecimal unitsToReduce = quantity.add(feeInBaseUnits);
      if (state.units.compareTo(BigDecimal.ZERO) <= 0 || unitsToReduce.compareTo(BigDecimal.ZERO) <= 0) {
        return;
      }
      BigDecimal reducibleUnits = unitsToReduce.min(state.units);
      BigDecimal avgCost = state.costEur.compareTo(BigDecimal.ZERO) <= 0
          ? BigDecimal.ZERO
          : state.costEur.divide(state.units, 16, RoundingMode.HALF_UP);
      BigDecimal costReduction = avgCost.multiply(reducibleUnits);
      state.units = state.units.subtract(reducibleUnits).max(BigDecimal.ZERO);
      state.costEur = state.costEur.subtract(costReduction).max(BigDecimal.ZERO);
    }
  }

  private static String extractAssetSymbol(BitvavoClient.Transaction t) {
    if (t == null) {
      return null;
    }
    String value = t.market();
    if (value != null && !value.isBlank()) {
      int separator = value.indexOf('-');
      String base = separator > 0 ? value.substring(0, separator) : value;
      if (!base.isBlank()) {
        return base.toUpperCase(Locale.ROOT);
      }
    }
    value = t.symbol();
    if (value != null && !value.isBlank()) {
      int separator = value.indexOf('-');
      String base = separator > 0 ? value.substring(0, separator) : value;
      if (!base.isBlank()) {
        return base.toUpperCase(Locale.ROOT);
      }
    }
    return null;
  }

  private static String extractQuoteCurrency(BitvavoClient.Transaction t) {
    if (t == null) {
      return null;
    }
    String value = t.market();
    if (value == null || value.isBlank()) {
      value = t.symbol();
    }
    if (value != null && !value.isBlank()) {
      int separator = value.indexOf('-');
      if (separator > 0 && separator < value.length() - 1) {
        return value.substring(separator + 1).toUpperCase(Locale.ROOT);
      }
    }
    return "EUR";
  }

  private static BigDecimal resolveTradeValueEur(BitvavoClient.Transaction t,
                                                 String quoteCurrency,
                                                 Map<String, BigDecimal> priceByMarket,
                                                 Map<String, BigDecimal> fallbackPrices) {
    BigDecimal quoteValue;
    if (t.amountQuote() != null) {
      quoteValue = t.amountQuote().abs();
    } else if (t.amount() != null && t.price() != null) {
      quoteValue = t.amount().abs().multiply(t.price().abs());
    } else {
      return null;
    }
    BigDecimal quoteToEurRate = resolveQuoteToEurRate(quoteCurrency, priceByMarket, fallbackPrices);
    if (quoteToEurRate == null) {
      return null;
    }
    return quoteValue.multiply(quoteToEurRate);
  }

  private static BigDecimal resolveFeeEur(BitvavoClient.Transaction t,
                                          String quoteCurrency,
                                          Map<String, BigDecimal> priceByMarket,
                                          Map<String, BigDecimal> fallbackPrices) {
    if (t.fee() == null || t.feeCurrency() == null) {
      return BigDecimal.ZERO;
    }
    String feeCurrency = t.feeCurrency().toUpperCase(Locale.ROOT);
    if (feeCurrency.equalsIgnoreCase(extractAssetSymbol(t))) {
      return BigDecimal.ZERO;
    }
    BigDecimal rate = resolveQuoteToEurRate(feeCurrency, priceByMarket, fallbackPrices);
    if (rate == null) {
      // Fallback: if fee currency equals quote currency and quote conversion is known.
      rate = resolveQuoteToEurRate(quoteCurrency, priceByMarket, fallbackPrices);
    }
    if (rate == null) {
      return BigDecimal.ZERO;
    }
    return t.fee().abs().multiply(rate);
  }

  private static BigDecimal resolveFeeInBaseUnits(BitvavoClient.Transaction t, String baseSymbol) {
    if (t.fee() == null || t.feeCurrency() == null || baseSymbol == null) {
      return BigDecimal.ZERO;
    }
    return baseSymbol.equalsIgnoreCase(t.feeCurrency()) ? t.fee().abs() : BigDecimal.ZERO;
  }

  private static String resolveSide(BitvavoClient.Transaction t) {
    if (t == null) {
      return null;
    }
    if (t.side() != null && !t.side().isBlank()) {
      String side = t.side().toLowerCase(Locale.ROOT);
      if ("buy".equals(side) || "sell".equals(side)) {
        return side;
      }
    }
    if (t.type() != null && !t.type().isBlank()) {
      String type = t.type().toLowerCase(Locale.ROOT);
      if (type.contains("buy")) {
        return "buy";
      }
      if (type.contains("sell")) {
        return "sell";
      }
    }
    return null;
  }

  private static String resolveExternalTransactionId(BitvavoClient.Transaction t) {
    if (t == null) {
      return null;
    }
    if (t.id() != null && !t.id().isBlank()) {
      return t.id();
    }
    String asset = extractAssetSymbol(t);
    String side = resolveSide(t);
    if (asset == null || side == null || t.timestamp() == null || t.amount() == null) {
      return null;
    }
    return "bitvavo-synth:" + asset + ":" + side + ":" + t.timestamp() + ":" + t.amount().toPlainString()
        + ":" + (t.price() == null ? "0" : t.price().toPlainString());
  }

  private static BigDecimal resolveQuoteToEurRate(String quoteCurrency,
                                                  Map<String, BigDecimal> priceByMarket,
                                                  Map<String, BigDecimal> fallbackPrices) {
    if (quoteCurrency == null || quoteCurrency.isBlank()) {
      return BigDecimal.ONE;
    }
    String quote = quoteCurrency.toUpperCase(Locale.ROOT);
    if ("EUR".equals(quote)) {
      return BigDecimal.ONE;
    }
    BigDecimal direct = priceByMarket.get(quote + "-EUR");
    if (direct != null && direct.compareTo(BigDecimal.ZERO) > 0) {
      return direct;
    }
    BigDecimal inverse = priceByMarket.get("EUR-" + quote);
    if (inverse != null && inverse.compareTo(BigDecimal.ZERO) > 0) {
      return BigDecimal.ONE.divide(inverse, 16, RoundingMode.HALF_UP);
    }
    BigDecimal viaFallback = fallbackPrices.get(quote);
    if (viaFallback != null && viaFallback.compareTo(BigDecimal.ZERO) > 0) {
      return viaFallback;
    }
    BigDecimal quoteUsdt = priceByMarket.get(quote + "-USDT");
    BigDecimal usdtEur = priceByMarket.get("USDT-EUR");
    if (quoteUsdt != null && usdtEur != null && quoteUsdt.compareTo(BigDecimal.ZERO) > 0 && usdtEur.compareTo(BigDecimal.ZERO) > 0) {
      return quoteUsdt.multiply(usdtEur);
    }
    return null;
  }

  private static final class InvestState {
    private BigDecimal units = BigDecimal.ZERO;
    private BigDecimal costEur = BigDecimal.ZERO;
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
