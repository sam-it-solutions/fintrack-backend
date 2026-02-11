package com.fintrack.repository;

import com.fintrack.model.FinancialAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FinancialAccountRepository extends JpaRepository<FinancialAccount, UUID> {
  List<FinancialAccount> findByUserId(UUID userId);
  List<FinancialAccount> findByUserIdOrHouseholdIdIn(UUID userId, List<UUID> householdIds);
  List<FinancialAccount> findByConnectionId(UUID connectionId);
  Optional<FinancialAccount> findByConnectionIdAndExternalId(UUID connectionId, String externalId);

  @Query("select a from FinancialAccount a " +
      "left join a.connection c " +
      "where a.user.id = :userId " +
      "and (c is null or c.status <> com.fintrack.model.ConnectionStatus.DISABLED)")
  List<FinancialAccount> findActiveByUserId(@Param("userId") UUID userId);

  @Query("select a from FinancialAccount a " +
      "left join a.connection c " +
      "where (a.user.id = :userId or a.household.id in :householdIds) " +
      "and (c is null or c.status <> com.fintrack.model.ConnectionStatus.DISABLED)")
  List<FinancialAccount> findActiveByUserIdOrHouseholdIdIn(
      @Param("userId") UUID userId,
      @Param("householdIds") List<UUID> householdIds);
}
