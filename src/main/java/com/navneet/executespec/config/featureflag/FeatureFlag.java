package com.navneet.executespec.config.featureflag;

import java.time.Instant;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "feature_flag")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureFlag {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "flag_key", nullable = false, unique = true, length = 128)
  private String flagKey;

  @Column(name = "flag_value", nullable = false, length = 512)
  private String flagValue;

  @Enumerated(EnumType.STRING)
  @Column(name = "value_type", nullable = false, length = 20)
  private ValueType valueType;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope", nullable = false, length = 20)
  private Scope scope;

  @Column(name = "description", length = 512)
  private String description;

  @Column(name = "updated_by")
  private String updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public enum ValueType {
    BOOLEAN,
    STRING,
    INTEGER,
    PERCENTAGE
  }

  public enum Scope {
    // PLATFORM and GLOBAL are synonyms (platform-wide); both appear in seed migrations
    // (e.g. V96 auto-push, V202 reviewer third-pass). GLOBAL MUST be a constant or
    // repository.findAll() throws "No enum constant ...Scope.GLOBAL" — which empties the
    // ENTIRE feature-flag cache on every reload and silently disables every flag
    // system-wide (incl. scoring.additive-v2). One bad row should never do that.
    PLATFORM,
    GLOBAL,
    ORG
  }

  @PrePersist
  @PreUpdate
  void setTimestamp() {
    this.updatedAt = Instant.now();
  }
}
