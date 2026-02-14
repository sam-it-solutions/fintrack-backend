package com.fintrack.provider.bitvavo;

import com.fintrack.config.BitvavoProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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

  private final RestClient restClient;

  public BitvavoClient(BitvavoProperties properties) {
    this.restClient = RestClient.builder()
        .baseUrl(properties.baseUrl())
        .build();
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
    try {
      List<Transaction> result = requestTransactions(apiKey, apiSecret, "/transactions");
      log.info("Bitvavo API /transactions returned {}", result == null ? 0 : result.size());
      if (result != null) {
        collected.addAll(result);
      }
    } catch (HttpClientErrorException.NotFound ex) {
      log.info("Bitvavo API /transactions not found");
    } catch (Exception ex) {
      log.warn("Bitvavo API /transactions failed: {}", ex.getMessage());
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
      log.info("Bitvavo API /trades returned {}", tradesAllMarkets.size());
      collected.addAll(tradesAllMarkets);
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
    SignedHeaders headers = signedHeaders(apiKey, apiSecret, "GET", path, "");
    return restClient.get()
        .uri(path)
        .header("Bitvavo-Access-Key", headers.apiKey())
        .header("Bitvavo-Access-Signature", headers.signature())
        .header("Bitvavo-Access-Timestamp", headers.timestamp())
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .header(HttpHeaders.USER_AGENT, USER_AGENT)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
  }

  private List<Transaction> requestTrades(String apiKey, String apiSecret, String market) {
    String path = "/trades?market=" + market;
    SignedHeaders headers = signedHeaders(apiKey, apiSecret, "GET", path, "");
    return restClient.get()
        .uri(path)
        .header("Bitvavo-Access-Key", headers.apiKey())
        .header("Bitvavo-Access-Signature", headers.signature())
        .header("Bitvavo-Access-Timestamp", headers.timestamp())
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .header(HttpHeaders.USER_AGENT, USER_AGENT)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
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

  public record Balance(String symbol, BigDecimal available, BigDecimal inOrder) {}

  public record Transaction(
      String id,
      String symbol,
      String market,
      BigDecimal amount,
      BigDecimal price,
      Long timestamp,
      String side,
      String type) {}

  public record Market(String market, String base, String quote, String status) {}

  public record TickerPrice(String market, String price) {}
}
