package com.navneet.executespec.memory.repository;

import com.navneet.executespec.memory.entity.ProjectChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectChatMessageRepository extends JpaRepository<ProjectChatMessage, String> {
    List<ProjectChatMessage> findByProjectIdOrderByCreatedAtAsc(String projectId);
}
