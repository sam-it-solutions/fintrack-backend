package com.fintrack.service;

import com.fintrack.model.TransactionDirection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TransactionCategorizer {
  private final Map<String, String[]> rules = new LinkedHashMap<>();

  public TransactionCategorizer() {
    rules.put("Boodschappen", new String[]{"carrefour", "colruyt", "delhaize", "aldi", "lidl", "spar", "ah", "albert heijn", "okay", "bioplanet", "supermarket"});
    rules.put("Horeca", new String[]{"restaurant", "cafe", "bar", "starbucks", "takeaway", "uber eats", "ubereats", "deliveroo", "snackbar", "pizza"});
    rules.put("Transport", new String[]{"sncb", "nmbs", "uber", "bolt", "taxi", "shell", "total", "q8", "parking", "train", "tram", "bus"});
    rules.put("Shopping", new String[]{"amazon", "bol.com", "coolblue", "zalando", "ikea", "mediamarkt", "decathlon"});
    rules.put("Abonnementen", new String[]{"netflix", "spotify", "hbo", "prime", "disney", "apple.com/bill", "google", "icloud"});
    rules.put("Utilities", new String[]{"engie", "luminus", "proximus", "telenet", "orange", "water", "energie"});
    rules.put("Huur/Hypotheek", new String[]{"huur", "rent", "hypotheek", "mortgage"});
    rules.put("Gezondheid", new String[]{"apotheek", "pharmacy", "dokter", "ziekenhuis", "hospital", "kliniek"});
    rules.put("Onderwijs", new String[]{"school", "university", "opleiding", "course", "college"});
    rules.put("Cash", new String[]{"atm", "geldautomaat", "cash withdrawal"});
  }

  public String categorize(String description, TransactionDirection direction) {
    return categorize(description, direction, null);
  }

  public String categorize(String description, TransactionDirection direction, String transactionType) {
    return categorizeDetailed(description, direction, transactionType).category();
  }

  public RuleMatch categorizeDetailed(String description, TransactionDirection direction, String transactionType) {
    if (transactionType != null && transactionType.equalsIgnoreCase("TRANSFER")) {
      return new RuleMatch("Transfer", "Type TRANSFER");
    }
    if (direction == TransactionDirection.IN) {
      return new RuleMatch("Inkomen", "Inkomende transactie");
    }
    String normalized = description == null ? "" : description.toLowerCase(Locale.ROOT);
    for (Map.Entry<String, String[]> entry : rules.entrySet()) {
      for (String keyword : entry.getValue()) {
        if (normalized.contains(keyword)) {
          return new RuleMatch(entry.getKey(), "Match op '" + keyword + "'");
        }
      }
    }
    return new RuleMatch("Overig", "Geen match");
  }

  public record RuleMatch(String category, String reason) {}
}
