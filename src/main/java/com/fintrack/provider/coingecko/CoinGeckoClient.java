package com.fintrack.provider.coingecko;

import com.fintrack.config.CoinGeckoProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class CoinGeckoClient {
  private static final Logger log = LoggerFactory.getLogger(CoinGeckoClient.class);
  private static final int MAX_SYMBOLS_PER_REQUEST = 50;
  private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(10);
  private static final Duration DEFAULT_SYMBOL_CACHE_TTL = Duration.ofHours(12);
  private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[a-z0-9-]{1,32}$");

  private final RestClient restClient;
  private final Duration cacheTtl;
  private final Map<String, CachedPrice> priceCache = new ConcurrentHashMap<>();
  private final Map<String, String> symbolToIdCache = new ConcurrentHashMap<>();
  private Instant symbolCacheUpdatedAt = Instant.EPOCH;

  private static final Map<String, String> SYMBOL_OVERRIDES = Map.of(
      "theta", "theta-token",
      "theta-token", "theta-token"
  );

  public CoinGeckoClient(CoinGeckoProperties properties) {
    String baseUrl = StringUtils.hasText(properties.baseUrl())
        ? properties.baseUrl()
        : "https://api.coingecko.com/api/v3";
    RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
    if (StringUtils.hasText(properties.apiKey())) {
      String header = StringUtils.hasText(properties.apiKeyHeader())
          ? properties.apiKeyHeader()
          : "x-cg-demo-api-key";
      builder.defaultHeader(header, properties.apiKey());
    }
    builder.defaultHeader(HttpHeaders.USER_AGENT, "Fintrack/1.0");
    this.restClient = builder.build();
    this.cacheTtl = properties.cacheTtl() != null ? properties.cacheTtl() : DEFAULT_CACHE_TTL;
  }

  public Map<String, BigDecimal> getEurPricesBySymbols(Collection<String> symbols) {
    Map<String, BigDecimal> result = new HashMap<>();
    if (symbols == null || symbols.isEmpty()) {
      return result;
    }
    List<String> normalized = symbols.stream()
        .filter(StringUtils::hasText)
        .map(s -> s.trim().toLowerCase(Locale.ROOT))
        .distinct()
        .toList();
    Instant now = Instant.now();
    List<String> toFetch = new ArrayList<>();
    for (String symbol : normalized) {
      CachedPrice cached = priceCache.get(symbol);
      if (cached != null && !cached.isExpired(now, cacheTtl)) {
        result.put(symbol.toUpperCase(Locale.ROOT), cached.price());
      } else {
        toFetch.add(symbol);
      }
    }
    if (!toFetch.isEmpty()) {
      ensureSymbolCache(now);
      for (int i = 0; i < toFetch.size(); i += MAX_SYMBOLS_PER_REQUEST) {
        List<String> chunk = toFetch.subList(i, Math.min(i + MAX_SYMBOLS_PER_REQUEST, toFetch.size()));
        fetchChunk(chunk, now);
      }
      for (String symbol : normalized) {
        CachedPrice cached = priceCache.get(symbol);
        if (cached != null && cached.price() != null) {
          result.putIfAbsent(symbol.toUpperCase(Locale.ROOT), cached.price());
        }
      }
    }
    return result;
  }

  private void fetchChunk(List<String> symbols, Instant now) {
    if (symbols.isEmpty()) {
      return;
    }
    try {
      List<String> ids = new ArrayList<>();
      Map<String, String> idToSymbol = new HashMap<>();
      for (String symbol : symbols) {
        String id = resolveId(symbol);
        if (!StringUtils.hasText(id) && SYMBOL_PATTERN.matcher(symbol).matches()) {
          id = symbol;
        }
        if (!StringUtils.hasText(id)) {
          continue;
        }
        ids.add(id);
        idToSymbol.put(id, symbol);
      }
      if (ids.isEmpty()) {
        return;
      }

      Map<String, Map<String, BigDecimal>> response = restClient.get()
          .uri(uriBuilder -> uriBuilder
              .path("/simple/price")
              .queryParam("vs_currencies", "eur")
              .queryParam("ids", String.join(",", ids))
              .build())
          .retrieve()
          .body(new ParameterizedTypeReference<>() {});
      if (response == null || response.isEmpty()) {
        return;
      }
      for (Map.Entry<String, Map<String, BigDecimal>> entry : response.entrySet()) {
        String symbol = idToSymbol.get(entry.getKey());
        if (symbol == null) {
          continue;
        }
        Map<String, BigDecimal> price = entry.getValue();
        if (price == null) {
          continue;
        }
        BigDecimal eur = price.get("eur");
        if (eur != null) {
          priceCache.put(symbol, new CachedPrice(eur, now));
        }
      }
    } catch (Exception ex) {
      log.warn("CoinGecko price fetch failed: {}", ex.getMessage());
      // Keep cached values if the request fails.
    }
  }

  private void ensureSymbolCache(Instant now) {
    if (symbolToIdCache.isEmpty()) {
      symbolToIdCache.putAll(SYMBOL_OVERRIDES);
    }
    if (!symbolToIdCache.isEmpty() && symbolCacheUpdatedAt.plus(DEFAULT_SYMBOL_CACHE_TTL).isAfter(now)) {
      return;
    }
    try {
      List<CoinListItem> coins = restClient.get()
          .uri(uriBuilder -> uriBuilder.path("/coins/list").queryParam("include_platform", "false").build())
          .retrieve()
          .body(new ParameterizedTypeReference<>() {});
      if (coins == null || coins.isEmpty()) {
        return;
      }
      Map<String, List<CoinListItem>> bySymbol = new HashMap<>();
      for (CoinListItem item : coins) {
        if (item == null || !StringUtils.hasText(item.symbol()) || !StringUtils.hasText(item.id())) {
          continue;
        }
        String symbol = item.symbol().trim().toLowerCase(Locale.ROOT);
        if (!SYMBOL_PATTERN.matcher(symbol).matches()) {
          continue;
        }
        bySymbol.computeIfAbsent(symbol, key -> new ArrayList<>()).add(item);
      }
      Map<String, String> nextCache = new HashMap<>();
      for (Map.Entry<String, List<CoinListItem>> entry : bySymbol.entrySet()) {
        List<CoinListItem> items = entry.getValue();
        if (items.size() == 1) {
          nextCache.put(entry.getKey(), items.get(0).id());
        }
      }
      nextCache.putAll(SYMBOL_OVERRIDES);
      symbolToIdCache.clear();
      symbolToIdCache.putAll(nextCache);
      symbolCacheUpdatedAt = now;
    } catch (Exception ex) {
      log.warn("CoinGecko symbol cache refresh failed: {}", ex.getMessage());
      symbolToIdCache.putAll(SYMBOL_OVERRIDES);
      // Keep existing cache.
    }
  }

  private String resolveId(String symbol) {
    String cached = symbolToIdCache.get(symbol);
    if (StringUtils.hasText(cached)) {
      return cached;
    }
    if (symbolToIdCache.containsKey(symbol)) {
      return null;
    }
    String resolved = resolveViaSearch(symbol);
    if (StringUtils.hasText(resolved)) {
      symbolToIdCache.put(symbol, resolved);
      return resolved;
    }
    symbolToIdCache.put(symbol, "");
    return null;
  }

  private String resolveViaSearch(String symbol) {
    try {
      SearchResponse response = restClient.get()
          .uri(uriBuilder -> uriBuilder.path("/search").queryParam("query", symbol).build())
          .retrieve()
          .body(SearchResponse.class);
      if (response == null || response.coins() == null) {
        return null;
      }
      for (SearchCoin coin : response.coins()) {
        if (coin == null || !StringUtils.hasText(coin.symbol()) || !StringUtils.hasText(coin.id())) {
          continue;
        }
        if (symbol.equalsIgnoreCase(coin.symbol())) {
          return coin.id();
        }
      }
      return null;
    } catch (Exception ex) {
      log.warn("CoinGecko search failed for {}: {}", symbol, ex.getMessage());
      return null;
    }
  }

  private record CoinListItem(String id, String symbol, String name) {}

  private record SearchResponse(List<SearchCoin> coins) {}

  private record SearchCoin(String id, String symbol, String name) {}

  private record CachedPrice(BigDecimal price, Instant fetchedAt) {
    boolean isExpired(Instant now, Duration ttl) {
      return fetchedAt.plus(ttl).isBefore(now);
    }
  }
}
