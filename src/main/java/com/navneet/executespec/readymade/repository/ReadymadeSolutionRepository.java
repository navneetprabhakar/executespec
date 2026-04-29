package com.navneet.executespec.readymade.repository;

import com.navneet.executespec.readymade.entity.ReadymadeSolution;
import com.navneet.executespec.readymade.entity.ReadymadeSolution.Source;
import com.navneet.executespec.readymade.entity.ReadymadeSolution.Status;
import com.navneet.executespec.readymade.entity.ReadymadeSolution.Tier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * R-327 — basic CRUD + the filter-shaped queries the gallery API (W3)
 * will consume.  Search (FTS over the V157 GIN index) lands as a
 * native query in W3 — it would be premature here.
 */
public interface ReadymadeSolutionRepository extends JpaRepository<ReadymadeSolution, UUID> {

    Optional<ReadymadeSolution> findBySlug(String slug);

    List<ReadymadeSolution> findByStatus(Status status);

    List<ReadymadeSolution> findByCategoryAndStatus(String category, Status status);

    List<ReadymadeSolution> findByMacroGroupAndStatus(String macroGroup, Status status);

    List<ReadymadeSolution> findByTierAndStatus(Tier tier, Status status);

    List<ReadymadeSolution> findBySourceAndStatus(Source source, Status status);

    boolean existsBySlug(String slug);
}
