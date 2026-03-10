package com.navneet.executespec.integration.slack.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrgSlackIntegrationRepository extends JpaRepository<OrgSlackIntegration, Long> {

    Optional<OrgSlackIntegration> findByOrgId(String orgId);

    Optional<OrgSlackIntegration> findByTeamId(String teamId);

    void deleteByOrgId(String orgId);
}
