package com.navneet.executespec.readymade.repository;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.readymade.entity.ReadymadeDependency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface ReadymadeDependencyRepository
    extends JpaRepository<ReadymadeDependency, ReadymadeDependency.Id> {

  /** Outbound edges: "which solutions does {@code solutionId} depend on?" */
  List<ReadymadeDependency> findBySolutionId(UUID solutionId);

  /**
   * Inbound edges: "which solutions depend on {@code dependsOnId}?" Useful for delete-safety checks
   * (don't archive a primitive while a STACK still references it).
   */
  List<ReadymadeDependency> findByDependsOnId(UUID dependsOnId);

  /**
   * See {@link ReadymadeVariantRepository#deleteBySolutionId} — same reseeder-self-invocation
   * rationale for the @Transactional.
   */
  @Transactional
  void deleteBySolutionId(UUID solutionId);
}
