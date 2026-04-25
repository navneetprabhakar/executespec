package com.navneet.executespec.standards;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrgStandardRepository extends JpaRepository<OrgStandard, UUID> {
    List<OrgStandard> findByOrgId(String orgId);
    Optional<OrgStandard> findByOrgIdAndStandardId(String orgId, String standardId);
}
