package com.navneet.executespec.adr;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AdrRepository extends JpaRepository<Adr, UUID> {

    List<Adr> findByProjectId(String projectId);

    /** Drives E3.13 — only ACCEPTED ADRs flow into planner context. */
    @Query("SELECT a FROM Adr a WHERE a.projectId = :projectId AND a.status = 'ACCEPTED' "
            + "ORDER BY a.adrIdInRepo")
    List<Adr> findAcceptedForProject(@Param("projectId") String projectId);

    Optional<Adr> findByRepoIdAndAdrIdInRepo(UUID repoId, Integer adrIdInRepo);
}
