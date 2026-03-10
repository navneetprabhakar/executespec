package com.navneet.executespec.integration.slack.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectSlackChannelRepository extends JpaRepository<ProjectSlackChannel, Long> {

    List<ProjectSlackChannel> findByProjectId(String projectId);

    List<ProjectSlackChannel> findByOrgId(String orgId);

    Optional<ProjectSlackChannel> findByProjectIdAndChannelId(String projectId, String channelId);

    void deleteByProjectIdAndChannelId(String projectId, String channelId);

    void deleteByOrgId(String orgId);

    long countByOrgId(String orgId);
}
