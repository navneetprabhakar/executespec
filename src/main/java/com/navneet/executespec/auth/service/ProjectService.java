package com.navneet.executespec.auth.service;

import com.navneet.executespec.auth.dto.CreateProjectRequest;
import com.navneet.executespec.auth.dto.ProjectDto;
import com.navneet.executespec.auth.dto.ProjectMemberDto;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.ProjectMember;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final ProjectRepository projectRepo;
    private final ProjectMemberRepository projectMemberRepo;
    private final AppUserRepository userRepo;

    @Transactional
    public ProjectDto createProject(String orgId, String createdBy, CreateProjectRequest req) {
        Project project = Project.builder()
                .id(UUID.randomUUID().toString())
                .orgId(orgId)
                .name(req.name())
                .description(req.description())
                .status("ACTIVE")
                .createdBy(createdBy)
                .build();
        projectRepo.save(project);

        // Auto-add creator as PROJECT_ADMIN
        projectMemberRepo.save(ProjectMember.builder()
                .id(UUID.randomUUID().toString())
                .projectId(project.getId())
                .userId(createdBy)
                .projectRole("PROJECT_ADMIN")
                .build());

        log.info("Project created: {} ({}) in org {}", req.name(), project.getId(), orgId);
        return ProjectDto.from(project);
    }

    public List<ProjectDto> listProjectsForUser(String orgId, String userId, boolean isOrgAdmin) {
        if (isOrgAdmin) {
            return projectRepo.findByOrgId(orgId).stream()
                    .map(ProjectDto::from)
                    .collect(Collectors.toList());
        }
        // Developer — only projects they're a member of
        List<String> projectIds = projectMemberRepo.findProjectIdsByUserId(userId);
        return projectRepo.findAllById(projectIds).stream()
                .map(ProjectDto::from)
                .collect(Collectors.toList());
    }

    public ProjectDto getProject(String projectId, String userId, boolean isSuperAdmin, boolean isOrgAdmin) {
        Project project = projectRepo.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        if (!isSuperAdmin && !isOrgAdmin &&
                !projectMemberRepo.existsByProjectIdAndUserId(projectId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this project");
        }
        return ProjectDto.from(project);
    }

    @Transactional
    public ProjectDto archiveProject(String projectId) {
        Project project = projectRepo.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        project.setStatus("ARCHIVED");
        project.setUpdatedAt(Instant.now());
        return ProjectDto.from(projectRepo.save(project));
    }

    @Transactional
    public void addMember(String projectId, String userId, String role) {
        if (!projectMemberRepo.existsByProjectIdAndUserId(projectId, userId)) {
            projectMemberRepo.save(ProjectMember.builder()
                    .id(UUID.randomUUID().toString())
                    .projectId(projectId)
                    .userId(userId)
                    .projectRole(role != null ? role : "PROJECT_DEVELOPER")
                    .build());
        }
    }

    @Transactional
    public void removeMember(String projectId, String userId) {
        if (!projectMemberRepo.existsByProjectIdAndUserId(projectId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found in project");
        }
        projectMemberRepo.deleteByProjectIdAndUserId(projectId, userId);
    }

    public List<ProjectMemberDto> listMembers(String projectId) {
        return projectMemberRepo.findByProjectId(projectId).stream()
                .map(m -> {
                    AppUser user = userRepo.findById(m.getUserId()).orElse(null);
                    if (user == null) return null;
                    return ProjectMemberDto.from(user, m);
                })
                .filter(m -> m != null)
                .collect(Collectors.toList());
    }
}
