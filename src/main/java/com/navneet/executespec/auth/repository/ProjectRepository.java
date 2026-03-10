package com.navneet.executespec.auth.repository;

import com.navneet.executespec.auth.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, String> {

    List<Project> findByOrgId(String orgId);

    List<Project> findByOrgIdAndStatus(String orgId, String status);
}
