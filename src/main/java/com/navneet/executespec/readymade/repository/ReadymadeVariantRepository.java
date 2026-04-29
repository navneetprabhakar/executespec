package com.navneet.executespec.readymade.repository;

import com.navneet.executespec.readymade.entity.ReadymadeVariant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReadymadeVariantRepository extends JpaRepository<ReadymadeVariant, UUID> {

    List<ReadymadeVariant> findBySolutionId(UUID solutionId);

    /** Used by the W3 {@code VariantResolver} to pick the right variant
     *  based on the user repo's detected stack. */
    List<ReadymadeVariant> findBySolutionIdAndLanguageAndFramework(
        UUID solutionId, String language, String framework);

    /** All variants for a given (language, framework) cell — useful
     *  for "show me what's available for my stack" queries. */
    List<ReadymadeVariant> findByLanguageAndFramework(String language, String framework);

    void deleteBySolutionId(UUID solutionId);
}
