package com.navneet.executespec.sharing.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.sharing.entity.ProjectInvite;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectInviteRepository extends JpaRepository<ProjectInvite, UUID> {

  Optional<ProjectInvite> findByToken(String token);

  /** Pending invites for a project — used by the project Members tab. */
  List<ProjectInvite> findByProjectIdAndStatusOrderByCreatedAtDesc(String projectId, String status);

  /**
   * Pending invite for a (project, email) tuple — used to short-circuit re-invites and to attach a
   * pending invite to a freshly-signed-up user with the same email.
   */
  Optional<ProjectInvite> findFirstByProjectIdAndEmailIgnoreCaseAndStatusOrderByCreatedAtDesc(
      String projectId, String email, String status);

  /** Every pending invite addressed to a given email — used by the signup flow. */
  List<ProjectInvite> findByEmailIgnoreCaseAndStatus(String email, String status);
}
