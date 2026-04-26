package com.navneet.executespec.standards;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrgConfigRepoRepository extends JpaRepository<OrgConfigRepo, UUID> {
    Optional<OrgConfigRepo> findByOrgId(String orgId);
}
