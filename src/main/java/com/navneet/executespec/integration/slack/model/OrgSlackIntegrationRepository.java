package com.navneet.executespec.integration.slack.model;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgSlackIntegrationRepository extends JpaRepository<OrgSlackIntegration, Long> {

  Optional<OrgSlackIntegration> findByOrgId(String orgId);

  Optional<OrgSlackIntegration> findByTeamId(String teamId);

  void deleteByOrgId(String orgId);
}
