package com.navneet.executespec.deployment.credential;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DeploymentCredentialRepository
    extends JpaRepository<DeploymentCredential, String> {

  // User-level (default) credential queries

  Optional<DeploymentCredential> findByUserIdAndCloudProvider(String userId, String cloudProvider);

  Optional<DeploymentCredential> findByUserId(String userId);

  boolean existsByUserId(String userId);

  void deleteByUserIdAndCloudProvider(String userId, String cloudProvider);

  /** Find credential for a specific user + project combination. */
  Optional<DeploymentCredential> findByUserIdAndProjectIdAndCloudProvider(
      String userId, String projectId, String cloudProvider);

  /** Find the user's default credential (project_id IS NULL). */
  @Query(
      "SELECT c FROM DeploymentCredential c WHERE c.userId = :userId "
          + "AND c.projectId IS NULL AND c.cloudProvider = :cloudProvider")
  Optional<DeploymentCredential> findUserDefault(String userId, String cloudProvider);

  /** Find all credentials for a user (both project-level and default). */
  List<DeploymentCredential> findByUserIdOrderByProjectIdAsc(String userId);

  /** Delete credential for a specific project. */
  void deleteByUserIdAndProjectIdAndCloudProvider(
      String userId, String projectId, String cloudProvider);

  /** Delete user's default credential (project_id IS NULL). */
  @Query(
      "DELETE FROM DeploymentCredential c WHERE c.userId = :userId "
          + "AND c.projectId IS NULL AND c.cloudProvider = :cloudProvider")
  void deleteUserDefault(String userId, String cloudProvider);
}
