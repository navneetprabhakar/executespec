package com.navneet.executespec.deployment.credential;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeploymentCredentialRepository extends JpaRepository<DeploymentCredential, String> {

    Optional<DeploymentCredential> findByUserIdAndCloudProvider(String userId, String cloudProvider);

    Optional<DeploymentCredential> findByUserId(String userId);

    boolean existsByUserId(String userId);

    void deleteByUserIdAndCloudProvider(String userId, String cloudProvider);
}
