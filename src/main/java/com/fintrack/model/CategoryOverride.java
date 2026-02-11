package com.fintrack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "category_overrides")
@Getter
@Setter
public class CategoryOverride {
  @Id
  private UUID id;

  @ManyToOne(optional = false)
  @JoinColumn(name = "user_id")
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private MatchType matchType;

  @Column(nullable = false)
  private String matchValue;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private MatchMode matchMode;

  @Column(nullable = false)
  private String category;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  @PrePersist
  void prePersist() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
    if (updatedAt == null) {
      updatedAt = createdAt;
    }
    if (matchMode == null) {
      matchMode = MatchMode.CONTAINS;
    }
  }

  @PreUpdate
  void preUpdate() {
    if (matchMode == null) {
      matchMode = MatchMode.CONTAINS;
    }
  }

  public void touch() {
    updatedAt = Instant.now();
  }

  public enum MatchType {
    IBAN,
    MERCHANT,
    DESCRIPTION
  }

  public enum MatchMode {
    EXACT,
    CONTAINS
  }
}
