package com.navneet.executespec.auth.repository;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.auth.entity.OrgMember;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgMemberRepository extends JpaRepository<OrgMember, String> {

  Optional<OrgMember> findByOrgIdAndUserId(String orgId, String userId);

  List<OrgMember> findByOrgId(String orgId);

  List<OrgMember> findByUserId(String userId);

  boolean existsByOrgIdAndUserId(String orgId, String userId);

  void deleteByOrgIdAndUserId(String orgId, String userId);

  long countByOrgId(String orgId);

  long countByOrgIdAndOrgRole(String orgId, String orgRole);
}
