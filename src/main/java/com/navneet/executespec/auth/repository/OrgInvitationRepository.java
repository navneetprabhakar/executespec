package com.navneet.executespec.auth.repository;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.auth.entity.OrgInvitation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgInvitationRepository extends JpaRepository<OrgInvitation, String> {

  Optional<OrgInvitation> findByToken(String token);

  List<OrgInvitation> findByOrgId(String orgId);

  List<OrgInvitation> findByOrgIdAndStatus(String orgId, String status);

  List<OrgInvitation> findByStatus(String status);

  List<OrgInvitation> findByInvitedEmail(String email);
}
