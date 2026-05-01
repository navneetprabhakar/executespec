package com.navneet.executespec.auth.repository;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.auth.entity.ProjectMember;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, String> {

  List<ProjectMember> findByProjectId(String projectId);

  List<ProjectMember> findByUserId(String userId);

  Optional<ProjectMember> findByProjectIdAndUserId(String projectId, String userId);

  boolean existsByProjectIdAndUserId(String projectId, String userId);

  void deleteByProjectIdAndUserId(String projectId, String userId);

  @Query("SELECT pm.projectId FROM ProjectMember pm WHERE pm.userId = :userId")
  List<String> findProjectIdsByUserId(@Param("userId") String userId);

  int countByProjectIdAndProjectRole(String projectId, String projectRole);
}
