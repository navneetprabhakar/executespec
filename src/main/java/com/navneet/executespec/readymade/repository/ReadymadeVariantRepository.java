package com.navneet.executespec.readymade.repository;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.readymade.entity.ReadymadeVariant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface ReadymadeVariantRepository extends JpaRepository<ReadymadeVariant, UUID> {

  List<ReadymadeVariant> findBySolutionId(UUID solutionId);

  /**
   * Used by the W3 {@code VariantResolver} to pick the right variant based on the user repo's
   * detected stack.
   */
  List<ReadymadeVariant> findBySolutionIdAndLanguageAndFramework(
      UUID solutionId, String language, String framework);

  /**
   * All variants for a given (language, framework) cell — useful for "show me what's available for
   * my stack" queries.
   */
  List<ReadymadeVariant> findByLanguageAndFramework(String language, String framework);

  /**
   * Spring Data derived-name {@code deleteBy*} queries require an active transaction. Annotating
   * the method itself means it works whether the caller has a tx (admin controller path) or not
   * (boot-time {@code ReadymadeSeeder.@PostConstruct} which calls {@code reseed()} via {@code this}
   * and so bypasses the {@code @Transactional} proxy on the seeder).
   */
  @Transactional
  void deleteBySolutionId(UUID solutionId);
}
