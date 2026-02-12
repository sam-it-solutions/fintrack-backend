package com.fintrack.service;

import com.fintrack.dto.HouseholdBalanceMember;
import com.fintrack.dto.HouseholdBalanceResponse;
import com.fintrack.dto.HouseholdJoinRequest;
import com.fintrack.dto.HouseholdRequest;
import com.fintrack.dto.HouseholdResponse;
import com.fintrack.model.AccountTransaction;
import com.fintrack.model.TransactionDirection;
import com.fintrack.model.Household;
import com.fintrack.model.HouseholdMember;
import com.fintrack.model.HouseholdRole;
import com.fintrack.model.User;
import com.fintrack.repository.HouseholdMemberRepository;
import com.fintrack.repository.HouseholdRepository;
import com.fintrack.repository.AccountTransactionRepository;
import com.fintrack.repository.FinancialAccountRepository;
import com.fintrack.repository.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HouseholdService {
  private final HouseholdRepository householdRepository;
  private final HouseholdMemberRepository memberRepository;
  private final AccountTransactionRepository transactionRepository;
  private final FinancialAccountRepository accountRepository;
  private final UserRepository userRepository;

  public HouseholdService(HouseholdRepository householdRepository,
                          HouseholdMemberRepository memberRepository,
                          AccountTransactionRepository transactionRepository,
                          FinancialAccountRepository accountRepository,
                          UserRepository userRepository) {
    this.householdRepository = householdRepository;
    this.memberRepository = memberRepository;
    this.transactionRepository = transactionRepository;
    this.accountRepository = accountRepository;
    this.userRepository = userRepository;
  }

  public HouseholdResponse createHousehold(UUID userId, HouseholdRequest request) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

    Household household = new Household();
    household.setName(request.getName());
    household.setInviteCode(generateInviteCode());
    Household saved = householdRepository.save(household);

    HouseholdMember member = new HouseholdMember();
    member.setHousehold(saved);
    member.setUser(user);
    member.setRole(HouseholdRole.OWNER);
    memberRepository.save(member);

    return toResponse(saved, HouseholdRole.OWNER);
  }

  public HouseholdResponse joinHousehold(UUID userId, HouseholdJoinRequest request) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

    Household household = householdRepository.findByInviteCode(request.getInviteCode())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite code not found"));

    memberRepository.findByUserIdAndHouseholdId(userId, household.getId())
        .ifPresent(existing -> {
          throw new ResponseStatusException(HttpStatus.CONFLICT, "Already joined");
        });

    HouseholdMember member = new HouseholdMember();
    member.setHousehold(household);
    member.setUser(user);
    member.setRole(HouseholdRole.MEMBER);
    memberRepository.save(member);

    return toResponse(household, HouseholdRole.MEMBER);
  }

  public List<HouseholdResponse> listHouseholds(UUID userId) {
    return memberRepository.findByUserId(userId).stream()
        .map(m -> toResponse(m.getHousehold(), m.getRole()))
        .collect(Collectors.toList());
  }

  public HouseholdBalanceResponse balance(UUID userId, UUID householdId, YearMonth month, boolean includeShared) {
    memberRepository.findByUserIdAndHouseholdId(userId, householdId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a household member"));

    List<HouseholdMember> members = memberRepository.findByHouseholdId(householdId);
    List<UUID> userIds = members.stream()
        .map(m -> m.getUser().getId())
        .toList();

    LocalDate from = month.atDay(1);
    LocalDate to = month.atEndOfMonth();

    List<AccountTransaction> txs = transactionRepository.findHouseholdTransactionsInRange(userIds, householdId, from, to);

    Map<UUID, BigDecimal> paidBy = new HashMap<>();
    for (UUID id : userIds) {
      paidBy.put(id, BigDecimal.ZERO);
    }

    BigDecimal total = BigDecimal.ZERO;
    for (AccountTransaction tx : txs) {
      if (tx.getDirection() != TransactionDirection.OUT) {
        continue;
      }
      if ("Transfer".equalsIgnoreCase(tx.getCategory())) {
        continue;
      }
      boolean shared = tx.getAccount().getHousehold() != null
          && tx.getAccount().getHousehold().getId().equals(householdId);
      if (!includeShared && shared) {
        continue;
      }
      BigDecimal amount = tx.getAmount() == null ? BigDecimal.ZERO : tx.getAmount().abs();
      total = total.add(amount);
      if (!shared) {
        UUID ownerId = tx.getAccount().getUser().getId();
        paidBy.put(ownerId, paidBy.getOrDefault(ownerId, BigDecimal.ZERO).add(amount));
      }
    }

    BigDecimal share = members.isEmpty()
        ? BigDecimal.ZERO
        : total.divide(BigDecimal.valueOf(members.size()), 2, RoundingMode.HALF_UP);

    List<HouseholdBalanceMember> balances = new ArrayList<>();
    for (HouseholdMember member : members) {
      UUID memberId = member.getUser().getId();
      BigDecimal paid = paidBy.getOrDefault(memberId, BigDecimal.ZERO);
      BigDecimal balance = paid.subtract(share);
      balances.add(new HouseholdBalanceMember(
          memberId,
          member.getUser().getEmail(),
          paid,
          share,
          balance
      ));
    }

    return new HouseholdBalanceResponse(householdId, month.toString(), total, share, balances);
  }

  public void deleteHousehold(UUID userId, UUID householdId) {
    HouseholdMember membership = memberRepository.findByUserIdAndHouseholdId(userId, householdId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a household member"));
    if (membership.getRole() != HouseholdRole.OWNER) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can delete a household");
    }
    Household household = membership.getHousehold();
    List<com.fintrack.model.FinancialAccount> accounts = accountRepository.findByHouseholdId(householdId);
    if (!accounts.isEmpty()) {
      accounts.forEach(account -> account.setHousehold(null));
      accountRepository.saveAll(accounts);
    }
    memberRepository.deleteByHouseholdId(householdId);
    householdRepository.delete(household);
  }

  private String generateInviteCode() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
  }

  private HouseholdResponse toResponse(Household household, HouseholdRole role) {
    return new HouseholdResponse(household.getId(), household.getName(), household.getInviteCode(), role.name());
  }
}
