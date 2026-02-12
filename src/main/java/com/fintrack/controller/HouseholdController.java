package com.fintrack.controller;

import com.fintrack.dto.HouseholdBalanceResponse;
import com.fintrack.dto.HouseholdJoinRequest;
import com.fintrack.dto.HouseholdRequest;
import com.fintrack.dto.HouseholdResponse;
import com.fintrack.service.CurrentUserService;
import com.fintrack.service.HouseholdService;
import jakarta.validation.Valid;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/households")
public class HouseholdController {
  private final HouseholdService householdService;
  private final CurrentUserService currentUserService;

  public HouseholdController(HouseholdService householdService, CurrentUserService currentUserService) {
    this.householdService = householdService;
    this.currentUserService = currentUserService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public HouseholdResponse create(@Valid @RequestBody HouseholdRequest request) {
    UUID userId = currentUserService.requireUserId();
    return householdService.createHousehold(userId, request);
  }

  @PostMapping("/join")
  public HouseholdResponse join(@Valid @RequestBody HouseholdJoinRequest request) {
    UUID userId = currentUserService.requireUserId();
    return householdService.joinHousehold(userId, request);
  }

  @GetMapping
  public List<HouseholdResponse> list() {
    UUID userId = currentUserService.requireUserId();
    return householdService.listHouseholds(userId);
  }

  @GetMapping("/{householdId}/balance")
  public HouseholdBalanceResponse balance(@PathVariable UUID householdId,
                                          @RequestParam("month") String month,
                                          @RequestParam(value = "includeShared", defaultValue = "false") boolean includeShared) {
    UUID userId = currentUserService.requireUserId();
    YearMonth parsed = YearMonth.parse(month);
    return householdService.balance(userId, householdId, parsed, includeShared);
  }

  @DeleteMapping("/{householdId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID householdId) {
    UUID userId = currentUserService.requireUserId();
    householdService.deleteHousehold(userId, householdId);
  }

  @DeleteMapping("/{householdId}/members/{memberId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeMember(@PathVariable UUID householdId, @PathVariable UUID memberId) {
    UUID userId = currentUserService.requireUserId();
    householdService.removeMember(userId, householdId, memberId);
  }
}
