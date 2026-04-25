package com.navneet.executespec.memory.repository;

import com.navneet.executespec.memory.entity.ProjectChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ProjectChatMessageRepository extends JpaRepository<ProjectChatMessage, String> {
    List<ProjectChatMessage> findByProjectIdOrderByCreatedAtAsc(String projectId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ProjectChatMessage m WHERE m.projectId = :projectId")
    int deleteByProjectId(@Param("projectId") String projectId);
}
