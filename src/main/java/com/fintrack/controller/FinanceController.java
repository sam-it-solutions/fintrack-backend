package com.fintrack.controller;

import com.fintrack.dto.AccountResponse;
import com.fintrack.dto.AccountShareRequest;
import com.fintrack.dto.CreateAccountRequest;
import com.fintrack.dto.CreateTransactionRequest;
import com.fintrack.dto.RecategorizeResponse;
import com.fintrack.dto.RecurringPaymentResponse;
import com.fintrack.dto.SpendingCategorySummary;
import com.fintrack.dto.SummaryResponse;
import com.fintrack.dto.TransactionResponse;
import com.fintrack.dto.UpdateAccountRequest;
import com.fintrack.dto.UpdateTransactionCategoryRequest;
import com.fintrack.service.CurrentUserService;
import com.fintrack.service.FinanceService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/finance")
public class FinanceController {
  private final FinanceService financeService;
  private final CurrentUserService currentUserService;

  public FinanceController(FinanceService financeService, CurrentUserService currentUserService) {
    this.financeService = financeService;
    this.currentUserService = currentUserService;
  }

  @PostMapping("/accounts")
  @ResponseStatus(HttpStatus.CREATED)
  public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request) {
    UUID userId = currentUserService.requireUserId();
    return financeService.createAccount(userId, request);
  }

  @GetMapping("/accounts")
  public List<AccountResponse> listAccounts() {
    UUID userId = currentUserService.requireUserId();
    return financeService.listAccounts(userId);
  }

  @PostMapping("/accounts/{accountId}/sync")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void syncAccount(@PathVariable UUID accountId) {
    UUID userId = currentUserService.requireUserId();
    financeService.requestSync(userId, accountId);
  }

  @PatchMapping("/accounts/{accountId}/share")
  public AccountResponse shareAccount(@PathVariable UUID accountId, @Valid @RequestBody AccountShareRequest request) {
    UUID userId = currentUserService.requireUserId();
    return financeService.shareAccount(userId, accountId, request);
  }

  @PatchMapping("/accounts/{accountId}")
  public AccountResponse updateAccount(@PathVariable UUID accountId, @RequestBody UpdateAccountRequest request) {
    UUID userId = currentUserService.requireUserId();
    return financeService.updateAccount(userId, accountId, request);
  }

  @DeleteMapping("/accounts/{accountId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteAccount(@PathVariable UUID accountId) {
    UUID userId = currentUserService.requireUserId();
    financeService.deleteAccount(userId, accountId);
  }

  @GetMapping("/transactions")
  public List<TransactionResponse> listTransactions(
      @RequestParam("from") LocalDate from,
      @RequestParam("to") LocalDate to) {
    UUID userId = currentUserService.requireUserId();
    return financeService.listTransactions(userId, from, to);
  }

  @PostMapping("/transactions")
  @ResponseStatus(HttpStatus.CREATED)
  public TransactionResponse createTransaction(@Valid @RequestBody CreateTransactionRequest request) {
    UUID userId = currentUserService.requireUserId();
    return financeService.createTransaction(userId, request);
  }

  @PatchMapping("/transactions/{transactionId}/category")
  public TransactionResponse updateTransactionCategory(@PathVariable UUID transactionId,
                                                       @Valid @RequestBody UpdateTransactionCategoryRequest request) {
    UUID userId = currentUserService.requireUserId();
    return financeService.updateTransactionCategory(userId, transactionId, request);
  }

  @GetMapping("/summary")
  public SummaryResponse summary() {
    UUID userId = currentUserService.requireUserId();
    return financeService.getSummary(userId);
  }

  @GetMapping("/spending")
  public List<SpendingCategorySummary> spending(@RequestParam("month") String month) {
    UUID userId = currentUserService.requireUserId();
    YearMonth parsed = YearMonth.parse(month);
    return financeService.getSpendingByCategory(userId, parsed);
  }

  @GetMapping("/recurring")
  public List<RecurringPaymentResponse> recurring(@RequestParam(value = "months", defaultValue = "6") int months) {
    UUID userId = currentUserService.requireUserId();
    return financeService.getRecurringPayments(userId, months);
  }

  @PostMapping("/transactions/recategorize")
  public RecategorizeResponse recategorizeAll() {
    UUID userId = currentUserService.requireUserId();
    return financeService.recategorizeAll(userId);
  }

  @GetMapping("/transactions/ai")
  public List<TransactionResponse> listAiTransactions(
      @RequestParam(value = "limit", defaultValue = "50") int limit) {
    UUID userId = currentUserService.requireUserId();
    return financeService.listAiTransactions(userId, limit);
  }
}
