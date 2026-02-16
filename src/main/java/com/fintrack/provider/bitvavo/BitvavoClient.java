package com.fintrack.provider.bitvavo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.config.BitvavoProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class BitvavoClient {
  private static final String HMAC_SHA256 = "HmacSHA256";
  private static final String USER_AGENT = "Fintrack/1.0";
  private static final Logger log = LoggerFactory.getLogger(BitvavoClient.class);
  private static final String[] TX_ARRAY_KEYS = {"history", "transactions", "items", "rows", "data", "result"};
  // Bitvavo account history docs: maxItems/limit must be between 1 and 100.
  private static final int HISTORY_PAGE_SIZE = 100;

  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  public BitvavoClient(BitvavoProperties properties, ObjectMapper objectMapper) {
    this.restClient = RestClient.builder()
        .baseUrl(properties.baseUrl())
        .build();
    this.objectMapper = objectMapper;
  }

  public List<Balance> getBalances(String apiKey, String apiSecret) {
    SignedHeaders headers = signedHeaders(apiKey, apiSecret, "GET", "/balance", "");
    return restClient.get()
        .uri("/balance")
        .header("Bitvavo-Access-Key", headers.apiKey())
        .header("Bitvavo-Access-Signature", headers.signature())
        .header("Bitvavo-Access-Timestamp", headers.timestamp())
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .header(HttpHeaders.USER_AGENT, USER_AGENT)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
  }

  public List<Transaction> getTransactions(String apiKey, String apiSecret, List<String> markets) {
    List<Transaction> collected = new ArrayList<>();

    // Prefer paged history according to Bitvavo docs (page/maxItems, optional type).
    try {
      List<Transaction> paged = requestAccountHistoryAllByPage(apiKey, apiSecret, null);
      collected.addAll(paged);
      log.info("Bitvavo API /account/history paged full history returned {}", paged.size());
    } catch (Exception ex) {
      log.warn("Bitvavo API paged /account/history failed: {}", ex.getMessage());
    }

    // Some accounts seem to return incomplete mixed history; add explicit buy/sell pages as supplement.
    for (String type : List.of("buy", "sell")) {
      try {
        List<Transaction> typed = requestAccountHistoryAllByPage(apiKey, apiSecret, type);
        if (!typed.isEmpty()) {
          collected.addAll(typed);
          log.info("Bitvavo API /account/history type={} supplemental returned {}", type, typed.size());
        }
      } catch (Exception ex) {
        log.warn("Bitvavo API paged /account/history type={} failed: {}", type, ex.getMessage());
      }
    }

    long nowMs = Instant.now().toEpochMilli();
    if (collected.isEmpty()) {
      try {
        List<Transaction> result = requestTransactions(apiKey, apiSecret, "/account/history");
        log.info("Bitvavo API /account/history returned {}", result == null ? 0 : result.size());
        if (result != null) {
          collected.addAll(result);
        }
      } catch (Exception ex) {
        log.warn("Bitvavo API /account/history plain failed: {}", ex.getMessage());
      }
    }

    if (collected.isEmpty()) {
      try {
        List<Transaction> result = requestTransactions(apiKey, apiSecret, "/account/history?fromDate=0&toDate=" + nowMs);
        log.info("Bitvavo API /account/history?fromDate=0 returned {}", result == null ? 0 : result.size());
        if (result != null) {
          collected.addAll(result);
        }
      } catch (Exception ex) {
        log.warn("Bitvavo API /account/history?fromDate=0 failed: {}", ex.getMessage());
      }
    }

    if (markets != null && !markets.isEmpty()) {
      List<Transaction> tradesAllMarkets = new ArrayList<>();
      for (String market : markets) {
        try {
          List<Transaction> trades = requestTrades(apiKey, apiSecret, market);
          if (trades != null) {
            tradesAllMarkets.addAll(trades);
          }
        } catch (HttpClientErrorException.BadRequest badRequest) {
          log.warn("Bitvavo API /trades failed for market {}: {}", market, badRequest.getMessage());
        } catch (Exception innerEx) {
          log.warn("Bitvavo API /trades failed for market {}: {}", market, innerEx.getMessage());
        }
      }
      if (!tradesAllMarkets.isEmpty()) {
        log.info("Bitvavo API /trades supplemental returned {}", tradesAllMarkets.size());
        collected.addAll(tradesAllMarkets);
      }
    }

    if (collected.isEmpty()) {
      return List.of();
    }

    // Deduplicate transactions/trades that can overlap across endpoints.
    Map<String, Transaction> deduped = new LinkedHashMap<>();
    for (Transaction tx : collected) {
      if (tx == null) {
        continue;
      }
      String key = transactionKey(tx);
      deduped.putIfAbsent(key, tx);
    }
    return new ArrayList<>(deduped.values());
  }

  private List<Transaction> requestAccountHistoryAllByPage(String apiKey, String apiSecret, String type) {
    List<Transaction> all = new ArrayList<>();
    int maxPages = 200;
    int maxItems = HISTORY_PAGE_SIZE;
    Integer lastTotalPages = null;
    for (int page = 1; page <= maxPages; page++) {
      String path = "/account/history?page=" + page + "&maxItems=" + maxItems;
      if (type != null && !type.isBlank()) {
        path = path + "&type=" + type;
      }
      String raw = requestSignedJson(apiKey, apiSecret, path);
      TransactionsPage parsed = parseTransactionsPage(raw);
      List<Transaction> pageItems = parsed.items();
      log.info("Bitvavo API {} returned {}", path, pageItems.size());
      all.addAll(pageItems);
      if (pageItems.isEmpty()) {
        break;
      }
      if (pageItems.size() < maxItems) {
        break;
      }
      if (parsed.totalPages() != null) {
        lastTotalPages = parsed.totalPages();
      }
      if (lastTotalPages != null && page >= lastTotalPages) {
        break;
      }
    }
    return all;
  }

  private static String transactionKey(Transaction tx) {
    if (tx.id() != null && !tx.id().isBlank()) {
      return "id:" + tx.id();
    }
    return String.join("|",
        "m:" + (tx.market() == null ? "" : tx.market()),
        "s:" + (tx.symbol() == null ? "" : tx.symbol()),
        "t:" + (tx.timestamp() == null ? "" : tx.timestamp()),
        "a:" + (tx.amount() == null ? "" : tx.amount().toPlainString()),
        "p:" + (tx.price() == null ? "" : tx.price().toPlainString()),
        "d:" + (tx.side() == null ? "" : tx.side()));
  }

  public List<TickerPrice> getTickerPrices() {
    return restClient.get()
        .uri("/ticker/price")
        .header(HttpHeaders.USER_AGENT, USER_AGENT)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
  }

  public List<Market> getMarkets() {
    return restClient.get()
        .uri("/markets")
        .header(HttpHeaders.USER_AGENT, USER_AGENT)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
  }

  private List<Transaction> requestTransactions(String apiKey, String apiSecret, String path) {
    String raw = requestSignedJson(apiKey, apiSecret, path);
    return parseTransactionsPage(raw).items();
  }

  private List<Transaction> requestTrades(String apiKey, String apiSecret, String market) {
    String path = "/trades?market=" + market;
    String raw = requestSignedJson(apiKey, apiSecret, path);
    return parseTransactionsPage(raw).items();
  }

  private String requestSignedJson(String apiKey, String apiSecret, String path) {
    SignedHeaders headers = signedHeaders(apiKey, apiSecret, "GET", path, "");
    return restClient.get()
        .uri(path)
        .header("Bitvavo-Access-Key", headers.apiKey())
        .header("Bitvavo-Access-Signature", headers.signature())
        .header("Bitvavo-Access-Timestamp", headers.timestamp())
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .header(HttpHeaders.USER_AGENT, USER_AGENT)
        .retrieve()
        .body(String.class);
  }

  private TransactionsPage parseTransactionsPage(String raw) {
    if (raw == null || raw.isBlank()) {
      return new TransactionsPage(List.of(), null, null);
    }
    try {
      JsonNode root = objectMapper.readTree(raw);
      JsonNode arrayNode = findTransactionArray(root);
      if (arrayNode == null || !arrayNode.isArray()) {
        return new TransactionsPage(List.of(), intValue(root, "currentPage", "page"), intValue(root, "totalPages", "pages"));
      }
      List<Transaction> transactions = new ArrayList<>();
      for (JsonNode item : arrayNode) {
        Transaction tx = parseTransaction(item);
        if (tx != null) {
          transactions.add(tx);
        }
      }
      return new TransactionsPage(
          transactions,
          intValue(root, "currentPage", "page"),
          intValue(root, "totalPages", "pages"));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to parse Bitvavo transactions payload: " + abbreviate(raw), ex);
    }
  }

  private List<Transaction> requestAccountHistoryAllByOffset(String apiKey, String apiSecret, String type) {
    List<Transaction> all = new ArrayList<>();
    int start = 0;
    int limit = HISTORY_PAGE_SIZE;
    String previousFirstKey = null;
    for (int i = 0; i < 200; i++) {
      StringBuilder pathBuilder = new StringBuilder("/account/history?start=").append(start).append("&limit=").append(limit);
      if (type != null && !type.isBlank()) {
        pathBuilder.append("&type=").append(type);
      }
      String path = pathBuilder.toString();
      String raw = requestSignedJson(apiKey, apiSecret, path);
      TransactionsPage parsed = parseTransactionsPage(raw);
      List<Transaction> pageItems = parsed.items();
      log.info("Bitvavo API {} returned {}", path, pageItems.size());
      if (pageItems.isEmpty()) {
        break;
      }
      String firstKey = transactionKey(pageItems.get(0));
      if (previousFirstKey != null && previousFirstKey.equals(firstKey)) {
        log.warn("Bitvavo API {} appears to repeat first item; stopping pagination loop", path);
        break;
      }
      previousFirstKey = firstKey;
      all.addAll(pageItems);
      if (pageItems.isEmpty()) {
        break;
      }
      if (pageItems.size() < limit) {
        break;
      }
      start += pageItems.size();
    }
    return all;
  }

  private JsonNode findTransactionArray(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isArray()) {
      return node;
    }
    if (!node.isObject()) {
      return null;
    }
    for (String key : TX_ARRAY_KEYS) {
      JsonNode candidate = node.get(key);
      if (candidate != null && candidate.isArray()) {
        return candidate;
      }
    }
    JsonNode response = node.get("response");
    if (response != null) {
      JsonNode nested = findTransactionArray(response);
      if (nested != null) {
        return nested;
      }
    }
    JsonNode body = node.get("body");
    if (body != null) {
      JsonNode nested = findTransactionArray(body);
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }

  private Transaction parseTransaction(JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    String id = text(node, "id", "transactionId", "tradeId", "orderId", "uuid");
    String market = text(node, "market", "pair", "symbol");
    String symbol = text(node, "symbol", "asset", "baseCurrency");
    String sentCurrency = text(node, "sentCurrency");
    String receivedCurrency = text(node, "receivedCurrency");
    BigDecimal sentAmount = decimal(node, "sentAmount");
    BigDecimal receivedAmount = decimal(node, "receivedAmount");
    if (symbol == null && market != null && market.contains("-")) {
      symbol = market.substring(0, market.indexOf('-'));
    }

    String type = text(node, "type", "event", "eventType", "orderType");
    String side = text(node, "side", "action");
    if (side == null && type != null) {
      String normalized = type.toLowerCase();
      if ("buy".equals(normalized) || "sell".equals(normalized)) {
        side = normalized;
      }
    }
    BigDecimal amount = decimal(node, "amount", "quantity", "filledAmount", "baseAmount");
    BigDecimal price = decimal(node, "price", "fillPrice", "avgPrice", "averagePrice", "rate");
    BigDecimal amountQuote = decimal(node, "amountQuote", "quoteAmount", "filledAmountQuote", "cost");
    String quoteCurrency = text(node, "priceCurrency", "quoteCurrency");
    if (quoteCurrency == null && market != null && market.contains("-")) {
      quoteCurrency = market.substring(market.indexOf('-') + 1);
    }
    if (symbol == null && market != null && market.contains("-")) {
      symbol = market.substring(0, market.indexOf('-'));
    }
    if (side == null && symbol != null) {
      if (sentCurrency != null && sentCurrency.equalsIgnoreCase(symbol)) {
        side = "sell";
      } else if (receivedCurrency != null && receivedCurrency.equalsIgnoreCase(symbol)) {
        side = "buy";
      }
    }
    if (side == null && sentCurrency != null && receivedCurrency != null) {
      if (isLikelyQuoteCurrency(sentCurrency) && !isLikelyQuoteCurrency(receivedCurrency)) {
        side = "buy";
        if (symbol == null) {
          symbol = receivedCurrency;
        }
        if (quoteCurrency == null) {
          quoteCurrency = sentCurrency;
        }
      } else if (isLikelyQuoteCurrency(receivedCurrency) && !isLikelyQuoteCurrency(sentCurrency)) {
        side = "sell";
        if (symbol == null) {
          symbol = sentCurrency;
        }
        if (quoteCurrency == null) {
          quoteCurrency = receivedCurrency;
        }
      }
    }
    if (side != null && "buy".equalsIgnoreCase(side)) {
      // Legacy /account/history payload: sent = quote, received = base.
      if (amount == null) {
        amount = receivedAmount;
      }
      if (amountQuote == null) {
        amountQuote = sentAmount;
      }
      if (symbol == null) {
        symbol = receivedCurrency;
      }
      if (quoteCurrency == null) {
        quoteCurrency = sentCurrency;
      }
    } else if (side != null && "sell".equalsIgnoreCase(side)) {
      // Legacy /account/history payload: sent = base, received = quote.
      if (amount == null) {
        amount = sentAmount;
      }
      if (amountQuote == null) {
        amountQuote = receivedAmount;
      }
      if (symbol == null) {
        symbol = sentCurrency;
      }
      if (quoteCurrency == null) {
        quoteCurrency = receivedCurrency;
      }
    }
    if (amount == null && symbol != null) {
      if (sentCurrency != null && sentCurrency.equalsIgnoreCase(symbol)) {
        amount = sentAmount;
      } else if (receivedCurrency != null && receivedCurrency.equalsIgnoreCase(symbol)) {
        amount = receivedAmount;
      }
    }
    if (amountQuote == null && quoteCurrency != null) {
      if (sentCurrency != null && sentCurrency.equalsIgnoreCase(quoteCurrency)) {
        amountQuote = sentAmount;
      } else if (receivedCurrency != null && receivedCurrency.equalsIgnoreCase(quoteCurrency)) {
        amountQuote = receivedAmount;
      }
    }
    if (market == null && symbol != null && quoteCurrency != null) {
      market = symbol + "-" + quoteCurrency;
    }
    if (amountQuote == null && amount != null && price != null) {
      amountQuote = amount.abs().multiply(price.abs());
    }

    BigDecimal fee = decimal(node, "fee", "feeAmount", "takerFee", "makerFee");
    BigDecimal feesAmount = decimal(node, "feesAmount");
    if (fee == null) {
      fee = feesAmount;
    }
    String feeCurrency = text(node, "feeCurrency", "feeAsset", "feeSymbol", "feesCurrency");
    JsonNode feeNode = node.get("fee");
    if ((fee == null || feeCurrency == null) && feeNode != null && feeNode.isObject()) {
      if (fee == null) {
        fee = parseDecimal(feeNode);
      }
      if (feeCurrency == null) {
        feeCurrency = text(feeNode, "currency", "symbol", "asset");
      }
    }

    Long timestamp = longValue(node, "timestamp", "time", "created", "createdAt", "updated", "updatedAt", "date", "executedAt");

    return new Transaction(
        id,
        symbol,
        market,
        amount,
        amountQuote,
        price,
        timestamp,
        side,
        fee,
        feeCurrency,
        type);
  }

  private static String text(JsonNode node, String... keys) {
    for (String key : keys) {
      JsonNode candidate = node.get(key);
      if (candidate == null || candidate.isNull()) {
        continue;
      }
      if (candidate.isTextual()) {
        String value = candidate.asText();
        if (!value.isBlank()) {
          return value;
        }
      } else if (candidate.isNumber() || candidate.isBoolean()) {
        return candidate.asText();
      }
    }
    return null;
  }

  private static BigDecimal decimal(JsonNode node, String... keys) {
    for (String key : keys) {
      JsonNode candidate = node.get(key);
      BigDecimal parsed = parseDecimal(candidate);
      if (parsed != null) {
        return parsed;
      }
    }
    return null;
  }

  private static BigDecimal parseDecimal(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isNumber() || node.isTextual()) {
      try {
        String raw = node.asText();
        if (raw == null || raw.isBlank()) {
          return null;
        }
        return new BigDecimal(raw.trim());
      } catch (Exception ignored) {
        return null;
      }
    }
    if (node.isObject()) {
      BigDecimal nested = decimal(node, "amount", "value", "quantity", "units");
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }

  private static Long longValue(JsonNode node, String... keys) {
    for (String key : keys) {
      JsonNode candidate = node.get(key);
      if (candidate == null || candidate.isNull()) {
        continue;
      }
      if (candidate.isNumber()) {
        return candidate.asLong();
      }
      if (!candidate.isTextual()) {
        continue;
      }
      String value = candidate.asText();
      if (value == null || value.isBlank()) {
        continue;
      }
      try {
        return Long.parseLong(value.trim());
      } catch (Exception ignored) {
      }
      try {
        return Instant.parse(value.trim()).toEpochMilli();
      } catch (Exception ignored) {
      }
      try {
        return OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli();
      } catch (Exception ignored) {
      }
    }
    return null;
  }

  private static Integer intValue(JsonNode node, String... keys) {
    for (String key : keys) {
      JsonNode candidate = node.get(key);
      if (candidate == null || candidate.isNull()) {
        continue;
      }
      if (candidate.isInt() || candidate.isLong()) {
        return candidate.asInt();
      }
      if (candidate.isTextual()) {
        try {
          return Integer.parseInt(candidate.asText().trim());
        } catch (Exception ignored) {
        }
      }
    }
    return null;
  }

  private static boolean isLikelyQuoteCurrency(String currency) {
    if (currency == null || currency.isBlank()) {
      return false;
    }
    String value = currency.toUpperCase();
    return "EUR".equals(value) || "USD".equals(value) || "USDT".equals(value) || "USDC".equals(value) || "BTC".equals(value);
  }

  private static String abbreviate(String value) {
    if (value == null) {
      return "";
    }
    String compact = value.replaceAll("\\s+", " ").trim();
    return compact.length() <= 240 ? compact : compact.substring(0, 240) + "...";
  }

  private SignedHeaders signedHeaders(String apiKey, String apiSecret, String method, String path, String body) {
    String timestamp = String.valueOf(Instant.now().toEpochMilli());
    String signature = sign(apiSecret, timestamp + method + "/v2" + path + body);
    return new SignedHeaders(apiKey, signature, timestamp);
  }

  private String sign(String secret, String payload) {
    try {
      Mac mac = Mac.getInstance(HMAC_SHA256);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
      return bytesToHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to sign Bitvavo request", ex);
    }
  }

  private static String bytesToHex(byte[] bytes) {
    StringBuilder builder = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      builder.append(String.format("%02x", b));
    }
    return builder.toString();
  }

  public record SignedHeaders(String apiKey, String signature, String timestamp) {}

  private record TransactionsPage(List<Transaction> items, Integer currentPage, Integer totalPages) {}

  public record Balance(String symbol, BigDecimal available, BigDecimal inOrder) {}

  public record Transaction(
      String id,
      String symbol,
      String market,
      BigDecimal amount,
      BigDecimal amountQuote,
      BigDecimal price,
      Long timestamp,
      String side,
      BigDecimal fee,
      String feeCurrency,
      String type) {}

  public record Market(String market, String base, String quote, String status) {}

  public record TickerPrice(String market, String price) {}
}
