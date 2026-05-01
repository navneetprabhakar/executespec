package com.navneet.executespec.standards;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrgConfigRepoRepository extends JpaRepository<OrgConfigRepo, UUID> {
  Optional<OrgConfigRepo> findByOrgId(String orgId);
}
