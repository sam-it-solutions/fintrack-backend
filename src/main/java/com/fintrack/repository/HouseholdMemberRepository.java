package com.fintrack.repository;

import com.fintrack.model.HouseholdMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseholdMemberRepository extends JpaRepository<HouseholdMember, UUID> {
  @Query("select m from HouseholdMember m where m.user.id = :userId")
  List<HouseholdMember> findByUserId(@Param("userId") UUID userId);

  @Query("select m from HouseholdMember m where m.household.id = :householdId")
  List<HouseholdMember> findByHouseholdId(@Param("householdId") UUID householdId);

  @Query("select m from HouseholdMember m where m.user.id = :userId and m.household.id = :householdId")
  Optional<HouseholdMember> findByUserIdAndHouseholdId(
      @Param("userId") UUID userId,
      @Param("householdId") UUID householdId);
}
