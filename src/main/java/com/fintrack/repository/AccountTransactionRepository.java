package com.fintrack.repository;

import com.fintrack.model.AccountTransaction;
import com.fintrack.model.AccountType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, UUID> {
  @Query("select t from AccountTransaction t " +
      "where t.account.user.id = :userId " +
      "and (t.account.connection is null or t.account.connection.status <> com.fintrack.model.ConnectionStatus.DISABLED) " +
      "and t.bookingDate >= :from and t.bookingDate <= :to")
  List<AccountTransaction> findUserTransactionsInRange(
      @Param("userId") UUID userId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  @Query("select t from AccountTransaction t " +
      "where (t.account.user.id = :userId or t.account.household.id in :householdIds) " +
      "and (t.account.connection is null or t.account.connection.status <> com.fintrack.model.ConnectionStatus.DISABLED) " +
      "and t.bookingDate >= :from and t.bookingDate <= :to")
  List<AccountTransaction> findUserAndHouseholdTransactionsInRange(
      @Param("userId") UUID userId,
      @Param("householdIds") List<UUID> householdIds,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  @Query("select t from AccountTransaction t " +
      "where t.account.user.id = :userId " +
      "and (t.account.connection is null or t.account.connection.status <> com.fintrack.model.ConnectionStatus.DISABLED)")
  List<AccountTransaction> findUserTransactions(@Param("userId") UUID userId);

  @Query("select t from AccountTransaction t " +
      "where (t.account.user.id = :userId or t.account.household.id in :householdIds) " +
      "and (t.account.connection is null or t.account.connection.status <> com.fintrack.model.ConnectionStatus.DISABLED)")
  List<AccountTransaction> findUserAndHouseholdTransactions(
      @Param("userId") UUID userId,
      @Param("householdIds") List<UUID> householdIds);

  @Query("select t from AccountTransaction t " +
      "where t.account.user.id = :userId " +
      "and (t.account.connection is null or t.account.connection.status <> com.fintrack.model.ConnectionStatus.DISABLED) " +
      "and lower(coalesce(t.categorySource, '')) = 'ai' " +
      "order by t.bookingDate desc, t.createdAt desc")
  List<AccountTransaction> findUserAiTransactions(
      @Param("userId") UUID userId,
      Pageable pageable);

  @Query("select t from AccountTransaction t " +
      "where (t.account.user.id = :userId or t.account.household.id in :householdIds) " +
      "and (t.account.connection is null or t.account.connection.status <> com.fintrack.model.ConnectionStatus.DISABLED) " +
      "and lower(coalesce(t.categorySource, '')) = 'ai' " +
      "order by t.bookingDate desc, t.createdAt desc")
  List<AccountTransaction> findUserAndHouseholdAiTransactions(
      @Param("userId") UUID userId,
      @Param("householdIds") List<UUID> householdIds,
      Pageable pageable);

  @Query("select t from AccountTransaction t " +
      "where t.account.user.id = :userId " +
      "and (t.account.connection is null or t.account.connection.status <> com.fintrack.model.ConnectionStatus.DISABLED) " +
      "and t.account.type = :type and t.bookingDate >= :from and t.bookingDate <= :to")
  List<AccountTransaction> findUserTransactionsInRangeByType(
      @Param("userId") UUID userId,
      @Param("type") AccountType type,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  @Query("select t from AccountTransaction t " +
      "where (t.account.user.id = :userId or t.account.household.id in :householdIds) " +
      "and (t.account.connection is null or t.account.connection.status <> com.fintrack.model.ConnectionStatus.DISABLED) " +
      "and t.account.type = :type and t.bookingDate >= :from and t.bookingDate <= :to")
  List<AccountTransaction> findUserAndHouseholdTransactionsInRangeByType(
      @Param("userId") UUID userId,
      @Param("householdIds") List<UUID> householdIds,
      @Param("type") AccountType type,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  @Query("select coalesce(t.category, 'Overig'), t.currency, sum(t.amount) from AccountTransaction t " +
      "where (t.account.user.id = :userId or t.account.household.id in :householdIds) " +
      "and (t.account.connection is null or t.account.connection.status <> com.fintrack.model.ConnectionStatus.DISABLED) " +
      "and t.account.type = :type and t.direction = com.fintrack.model.TransactionDirection.OUT " +
      "and (t.category is null or (t.category <> 'Transfer' and t.category <> 'Crypto')) " +
      "and t.bookingDate >= :from and t.bookingDate <= :to group by coalesce(t.category, 'Overig'), t.currency")
  List<Object[]> sumByCategoryForUserAndHouseholds(
      @Param("userId") UUID userId,
      @Param("householdIds") List<UUID> householdIds,
      @Param("type") AccountType type,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  @Query("select coalesce(t.category, 'Overig'), t.currency, sum(t.amount) from AccountTransaction t " +
      "where t.account.user.id = :userId " +
      "and (t.account.connection is null or t.account.connection.status <> com.fintrack.model.ConnectionStatus.DISABLED) " +
      "and t.account.type = :type and t.direction = com.fintrack.model.TransactionDirection.OUT " +
      "and (t.category is null or (t.category <> 'Transfer' and t.category <> 'Crypto')) " +
      "and t.bookingDate >= :from and t.bookingDate <= :to group by coalesce(t.category, 'Overig'), t.currency")
  List<Object[]> sumByCategoryForUser(
      @Param("userId") UUID userId,
      @Param("type") AccountType type,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  @Query("select t from AccountTransaction t " +
      "where (t.account.user.id in :userIds or t.account.household.id = :householdId) " +
      "and (t.account.connection is null or t.account.connection.status <> com.fintrack.model.ConnectionStatus.DISABLED) " +
      "and t.bookingDate >= :from and t.bookingDate <= :to")
  List<AccountTransaction> findHouseholdTransactionsInRange(
      @Param("userIds") List<UUID> userIds,
      @Param("householdId") UUID householdId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  List<AccountTransaction> findByAccountId(UUID accountId);

  Optional<AccountTransaction> findFirstByAccountIdAndExternalIdOrderByCreatedAtAsc(UUID accountId, String externalId);

  void deleteByAccountId(UUID accountId);
}
