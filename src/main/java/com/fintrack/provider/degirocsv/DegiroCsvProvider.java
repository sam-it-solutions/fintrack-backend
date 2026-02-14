package com.fintrack.provider.degirocsv;

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
import java.io.BufferedReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DegiroCsvProvider implements ConnectionProvider {
  private static final Logger log = LoggerFactory.getLogger(DegiroCsvProvider.class);
  private static final String PROVIDER_ID = "degiro_csv";
  private static final String ACCOUNT_EXTERNAL_ID = "degiro-main";

  private final FinancialAccountRepository accountRepository;
  private final AccountTransactionRepository transactionRepository;
  private final CategoryService categoryService;
  private final SyncProgressService syncProgressService;

  public DegiroCsvProvider(FinancialAccountRepository accountRepository,
                           AccountTransactionRepository transactionRepository,
                           CategoryService categoryService,
                           SyncProgressService syncProgressService) {
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
    List<ProviderField> fields = List.of(
        new ProviderField(
            "portfolioCsv",
            "Portfolio overview CSV",
            true,
            false,
            "Upload of plak DEGIRO portfolio CSV"),
        new ProviderField(
            "accountCsv",
            "Account statement CSV (optioneel)",
            false,
            false,
            "Optioneel: upload DEGIRO account statement CSV"),
        new ProviderField(
            "transactionsCsv",
            "Transaction statement CSV (optioneel)",
            false,
            false,
            "Optioneel: upload transaction statement CSV"));
    return new ProviderResponse(PROVIDER_ID, "DEGIRO CSV", ConnectionType.INVESTMENT, false, fields);
  }

  @Override
  public ConnectResult initiate(Connection connection, Map<String, String> config) {
    return new ConnectResult(null, null, ConnectionStatus.ACTIVE);
  }

  @Override
  public SyncResult sync(Connection connection, Map<String, String> config) {
    String portfolioCsv = trimToNull(config.get("portfolioCsv"));
    String accountCsv = trimToNull(config.get("accountCsv"));
    String transactionsCsv = trimToNull(config.get("transactionsCsv"));
    if (portfolioCsv == null && accountCsv == null && transactionsCsv == null) {
      throw new IllegalArgumentException("DEGIRO CSV ontbreekt");
    }

    syncProgressService.update(connection, "Portfolio verwerken", 15);
    BigDecimal portfolioTotalEur = parsePortfolioTotalEur(portfolioCsv);

    syncProgressService.update(connection, "CSV inlezen", 30);
    List<DegiroRow> combined = new ArrayList<>();
    combined.addAll(parseCsv(accountCsv));
    combined.addAll(parseCsv(transactionsCsv));
    if (combined.isEmpty() && portfolioTotalEur == null) {
      throw new IllegalArgumentException("Geen transacties gevonden in DEGIRO CSV");
    }

    // Keep deterministic ordering and deduplicate rows across two exports.
    Map<String, DegiroRow> deduped = new LinkedHashMap<>();
    for (DegiroRow row : combined) {
      deduped.putIfAbsent(row.externalId, row);
    }
    List<DegiroRow> rows = deduped.values().stream()
        .sorted(Comparator.comparing((DegiroRow r) -> r.bookingDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(r -> r.externalId))
        .toList();

    syncProgressService.update(connection, "Rekening bijwerken", 45);
    FinancialAccount account = accountRepository.findByConnectionIdAndExternalId(connection.getId(), ACCOUNT_EXTERNAL_ID)
        .orElseGet(FinancialAccount::new);
    if (account.getId() == null) {
      account.setUser(connection.getUser());
      account.setConnection(connection);
      account.setType(AccountType.BANK);
      account.setProvider("DEGIRO");
      account.setExternalId(ACCOUNT_EXTERNAL_ID);
      account.setName(connection.getDisplayName() == null || connection.getDisplayName().isBlank()
          ? "DEGIRO"
          : connection.getDisplayName());
    }
    String currency = portfolioTotalEur != null
        ? "EUR"
        : rows.stream().map(r -> r.currency).filter(Objects::nonNull).findFirst().orElse("EUR");
    account.setCurrency(currency);
    BigDecimal latestBalance = portfolioTotalEur != null
        ? portfolioTotalEur
        : rows.stream()
            .sorted(Comparator.comparing((DegiroRow r) -> r.bookingDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> r.externalId).reversed())
            .map(r -> r.balance)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(account.getCurrentBalance());
    account.setCurrentBalance(latestBalance);
    account.setCurrentFiatValue("EUR".equalsIgnoreCase(currency) ? latestBalance : account.getCurrentFiatValue());
    account.setFiatCurrency("EUR".equalsIgnoreCase(currency) ? "EUR" : account.getFiatCurrency());
    account.setLastSyncedAt(Instant.now());
    account = accountRepository.save(account);

    syncProgressService.update(connection, "Transacties importeren", 70);
    int imported = 0;
    for (DegiroRow row : rows) {
      if (transactionRepository
          .findFirstByAccountIdAndExternalIdOrderByCreatedAtAsc(account.getId(), row.externalId)
          .isPresent()) {
        continue;
      }
      AccountTransaction tx = new AccountTransaction();
      tx.setAccount(account);
      tx.setAmount(row.amount.abs());
      tx.setCurrency(row.currency == null ? currency : row.currency);
      tx.setDirection(row.amount.signum() >= 0 ? TransactionDirection.IN : TransactionDirection.OUT);
      tx.setDescription(row.description);
      tx.setBookingDate(row.bookingDate);
      tx.setValueDate(row.valueDate);
      tx.setExternalId(row.externalId);
      tx.setProviderTransactionId(row.externalId);
      tx.setStatus("BOOKED");
      tx.setTransactionType(row.transactionType == null ? "DEGIRO_IMPORT" : row.transactionType);
      tx.setMerchantName(row.merchantName);
      tx.setCounterpartyIban(row.counterpartyIban);

      CategoryService.CategoryResult categoryResult = categoryService.categorizeDetailed(
          connection.getUser().getId(),
          tx.getDescription(),
          tx.getMerchantName(),
          tx.getDirection(),
          tx.getTransactionType(),
          account.getType(),
          tx.getCurrency(),
          tx.getAmount().toPlainString(),
          tx.getCounterpartyIban());
      tx.setCategory(categoryResult.category());
      tx.setCategorySource(categoryResult.source());
      if (categoryResult.confidence() != null) {
        tx.setCategoryConfidence(BigDecimal.valueOf(categoryResult.confidence()));
      }
      tx.setCategoryReason(categoryResult.reason());

      transactionRepository.save(tx);
      imported++;
    }
    syncProgressService.update(connection, "Afwerken", 95);
    log.info("DEGIRO CSV sync imported {} transactions for connection {}", imported, connection.getId());
    return new SyncResult(1, imported, "OK");
  }

  private BigDecimal parsePortfolioTotalEur(String rawCsv) {
    if (rawCsv == null || rawCsv.isBlank()) {
      return null;
    }
    String cleaned = rawCsv.replace("\uFEFF", "");
    try (BufferedReader reader = new BufferedReader(new StringReader(cleaned))) {
      String headerLine = reader.readLine();
      if (headerLine == null) {
        return null;
      }
      char delimiter = detectDelimiter(headerLine);
      List<String> headers = parseCsvLine(headerLine, delimiter).stream()
          .map(this::normalizeHeader)
          .toList();

      BigDecimal explicitTotal = null;
      BigDecimal summedPositions = BigDecimal.ZERO;
      int positionRows = 0;
      BigDecimal anyRowValues = BigDecimal.ZERO;
      int anyRows = 0;

      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        List<String> columns = parseCsvLine(line, delimiter);
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
          String value = i < columns.size() ? trimToNull(columns.get(i)) : null;
          values.put(headers.get(i), value);
        }
        BigDecimal eurValue = parseDecimal(firstNonBlank(values,
            "valueeur", "waardeeur", "valueineur", "waardeineur", "marketvalueeur",
            "marketvalue", "totalvalue", "totalewaarde", "value", "waarde"));
        if (eurValue == null) {
          continue;
        }
        String name = firstNonBlank(values, "product", "instrument", "symbol", "symbool", "naam", "description", "isin");
        String quantity = firstNonBlank(values, "quantity", "aantal", "positie", "position");
        String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        boolean totalRow = lowerName.contains("totaal") || lowerName.contains("total");

        anyRows++;
        anyRowValues = anyRowValues.add(eurValue);

        if (totalRow) {
          explicitTotal = eurValue.abs();
          continue;
        }
        if (name != null || quantity != null) {
          summedPositions = summedPositions.add(eurValue);
          positionRows++;
        }
      }

      if (explicitTotal != null) {
        return explicitTotal;
      }
      if (positionRows > 0) {
        return summedPositions.abs();
      }
      if (anyRows > 0) {
        return anyRowValues.abs();
      }
      return null;
    } catch (Exception ex) {
      throw new IllegalArgumentException("Portfolio CSV kan niet gelezen worden: " + ex.getMessage(), ex);
    }
  }

  private List<DegiroRow> parseCsv(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    String cleaned = raw.replace("\uFEFF", "");
    List<DegiroRow> rows = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new StringReader(cleaned))) {
      String headerLine = reader.readLine();
      if (headerLine == null) {
        return List.of();
      }
      char delimiter = detectDelimiter(headerLine);
      List<String> headers = parseCsvLine(headerLine, delimiter).stream()
          .map(this::normalizeHeader)
          .toList();

      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        List<String> columns = parseCsvLine(line, delimiter);
        if (columns.stream().allMatch(col -> col == null || col.isBlank())) {
          continue;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
          String value = i < columns.size() ? trimToNull(columns.get(i)) : null;
          values.put(headers.get(i), value);
        }
        DegiroRow row = mapRow(values);
        if (row != null) {
          rows.add(row);
        }
      }
    } catch (Exception ex) {
      throw new IllegalArgumentException("DEGIRO CSV kan niet gelezen worden: " + ex.getMessage(), ex);
    }
    return rows;
  }

  private DegiroRow mapRow(Map<String, String> values) {
    String amountRaw = firstNonBlank(values,
        "change", "amount", "value", "bedrag", "mutatie", "delta", "nettocashmovement");
    BigDecimal amount = parseDecimal(amountRaw);
    if (amount == null) {
      return null;
    }
    String bookingDateRaw = firstNonBlank(values, "date", "datum", "bookingdate", "transactiondate");
    String valueDateRaw = firstNonBlank(values, "valuedate", "valutadate", "valutadatum");
    LocalDate bookingDate = parseDate(bookingDateRaw);
    LocalDate valueDate = parseDate(valueDateRaw);
    String description = buildDescription(values);
    String currency = firstNonBlank(values, "currency", "valuta", "ccy");
    if (currency == null) {
      currency = "EUR";
    }
    String external = firstNonBlank(values,
        "orderid", "transactionid", "id", "reference", "referentie", "uuid");
    if (external == null) {
      String fingerprint = (bookingDate == null ? "" : bookingDate.toString())
          + "|" + (valueDate == null ? "" : valueDate.toString())
          + "|" + description
          + "|" + amount.toPlainString()
          + "|" + currency
          + "|" + String.valueOf(parseDecimal(firstNonBlank(values, "balance", "saldo", "runningbalance")));
      external = "degiro:" + sha256Hex(fingerprint);
    } else {
      external = "degiro:" + external.trim();
    }
    String txType = firstNonBlank(values, "type", "transactiontype", "ordertype");
    String merchant = firstNonBlank(values, "counterparty", "merchant", "product");
    String counterpartyIban = firstNonBlank(values, "iban", "counterpartyiban", "tegenrekeningiban");
    BigDecimal balance = parseDecimal(firstNonBlank(values, "balance", "saldo", "runningbalance"));
    return new DegiroRow(
        external,
        bookingDate,
        valueDate,
        description,
        currency.toUpperCase(Locale.ROOT),
        amount,
        balance,
        txType,
        merchant,
        counterpartyIban);
  }

  private String buildDescription(Map<String, String> values) {
    String description = firstNonBlank(values, "description", "omschrijving", "details");
    if (description != null) {
      return description;
    }
    String product = firstNonBlank(values, "product", "instrument", "symbol");
    String type = firstNonBlank(values, "type", "transactiontype", "ordertype");
    String note = firstNonBlank(values, "comment", "opmerking");
    String merged = String.join(" ",
        Optional.ofNullable(type).orElse(""),
        Optional.ofNullable(product).orElse(""),
        Optional.ofNullable(note).orElse("")).trim();
    return merged.isBlank() ? "DEGIRO transactie" : merged;
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : bytes) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception ex) {
      return Integer.toHexString(input.hashCode());
    }
  }

  private LocalDate parseDate(String raw) {
    String value = trimToNull(raw);
    if (value == null) {
      return null;
    }
    List<DateTimeFormatter> formats = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("d-M-uuuu"),
        DateTimeFormatter.ofPattern("d/M/uuuu"),
        DateTimeFormatter.ofPattern("uuuu/M/d"),
        DateTimeFormatter.ofPattern("d.MM.uuuu"));
    for (DateTimeFormatter f : formats) {
      try {
        return LocalDate.parse(value, f);
      } catch (DateTimeParseException ignored) {
      }
    }
    if (value.length() >= 10) {
      String firstPart = value.substring(0, 10);
      for (DateTimeFormatter f : formats) {
        try {
          return LocalDate.parse(firstPart, f);
        } catch (DateTimeParseException ignored) {
        }
      }
    }
    return null;
  }

  private BigDecimal parseDecimal(String raw) {
    String value = trimToNull(raw);
    if (value == null) {
      return null;
    }
    value = value.replace("\u00A0", "").replace(" ", "");
    value = value.replace("EUR", "").replace("€", "");
    boolean negative = value.startsWith("-") || value.startsWith("(") || value.endsWith(")");
    value = value.replace("(", "").replace(")", "").replace("+", "").replace("-", "");
    int lastComma = value.lastIndexOf(',');
    int lastDot = value.lastIndexOf('.');
    if (lastComma >= 0 && lastDot >= 0) {
      if (lastComma > lastDot) {
        value = value.replace(".", "").replace(',', '.');
      } else {
        value = value.replace(",", "");
      }
    } else if (lastComma >= 0) {
      value = value.replace(',', '.');
    }
    if (value.isBlank()) {
      return null;
    }
    try {
      BigDecimal parsed = new BigDecimal(value);
      return negative ? parsed.negate() : parsed;
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private char detectDelimiter(String headerLine) {
    long semicolons = headerLine.chars().filter(ch -> ch == ';').count();
    long commas = headerLine.chars().filter(ch -> ch == ',').count();
    return semicolons > commas ? ';' : ',';
  }

  private List<String> parseCsvLine(String line, char delimiter) {
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '"') {
        if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          current.append('"');
          i++;
        } else {
          inQuotes = !inQuotes;
        }
        continue;
      }
      if (ch == delimiter && !inQuotes) {
        out.add(current.toString());
        current.setLength(0);
        continue;
      }
      current.append(ch);
    }
    out.add(current.toString());
    return out;
  }

  private String normalizeHeader(String header) {
    if (header == null) {
      return "";
    }
    return header.toLowerCase(Locale.ROOT)
        .replace("\u00A0", " ")
        .replaceAll("[^a-z0-9]", "");
  }

  private String firstNonBlank(Map<String, String> values, String... keys) {
    for (String key : keys) {
      String normalized = normalizeHeader(key);
      String value = values.get(normalized);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isBlank() ? null : trimmed;
  }

  private record DegiroRow(
      String externalId,
      LocalDate bookingDate,
      LocalDate valueDate,
      String description,
      String currency,
      BigDecimal amount,
      BigDecimal balance,
      String transactionType,
      String merchantName,
      String counterpartyIban) {
  }
}
