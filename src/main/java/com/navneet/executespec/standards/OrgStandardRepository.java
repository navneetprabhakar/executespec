package com.navneet.executespec.standards;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrgStandardRepository extends JpaRepository<OrgStandard, UUID> {
  List<OrgStandard> findByOrgId(String orgId);

  Optional<OrgStandard> findByOrgIdAndStandardId(String orgId, String standardId);
}
