package com.navneet.executespec.auth.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.auth.entity.OrgInvitation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrgInvitationRepository extends JpaRepository<OrgInvitation, String> {

  /**
   * Atomically transitions a PENDING invitation to ACCEPTED (RR-222). Returns 1 for the single
   * winner and 0 for every loser, so membership creation can be gated on winning the claim rather
   * than on a read-then-write status check that two concurrent holders of the same token both pass.
   */
  @Modifying
  @Query(
      "UPDATE OrgInvitation i SET i.status = 'ACCEPTED', i.resolvedAt = :now WHERE i.id = :id AND"
          + " i.status = 'PENDING'")
  int claimPending(@Param("id") String id, @Param("now") Instant now);

  Optional<OrgInvitation> findByToken(String token);

  List<OrgInvitation> findByOrgId(String orgId);

  List<OrgInvitation> findByOrgIdAndStatus(String orgId, String status);

  List<OrgInvitation> findByStatus(String status);

  List<OrgInvitation> findByInvitedEmail(String email);
}
