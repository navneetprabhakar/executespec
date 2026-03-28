package com.navneet.executespec.auth.service;

import com.navneet.executespec.auth.dto.CreateProjectRequest;
import com.navneet.executespec.auth.dto.ProjectDto;
import com.navneet.executespec.auth.dto.ProjectMemberDto;
import com.navneet.executespec.auth.dto.UpdateProjectSettingsRequest;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.auth.entity.ActivityAudit;
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
    private final ActivityAuditService auditService;

    @Transactional
    public ProjectDto createProject(String orgId, String createdBy, CreateProjectRequest req) {
        LlmProvider provider = LlmProvider.ANTHROPIC;
        if (req.aiProvider() != null) {
            try { provider = LlmProvider.valueOf(req.aiProvider().toUpperCase()); }
            catch (IllegalArgumentException ignored) { /* default ANTHROPIC */ }
        }

        Project project = Project.builder()
                .id(UUID.randomUUID().toString())
                .orgId(orgId)
                .name(req.name())
                .description(req.description())
                .aiProvider(provider)
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

        auditService.record(ActivityAudit.builder()
                .eventType("PROJECT_CREATED")
                .entityType("PROJECT").entityId(project.getId())
                .orgId(orgId).actorId(createdBy)
                .newValue("{\"name\":\"" + req.name() + "\",\"orgId\":\"" + orgId + "\"}")
                .build());

        log.info("Project created: {} ({}) in org {}", req.name(), project.getId(), orgId);
        return ProjectDto.from(project);
    }

    public List<ProjectDto> listProjectsForUser(String orgId, String userId, boolean isOrgAdmin) {
        // All org members can see all org projects — projects are a shared grouping tool.
        // Write operations (create/archive/manage members) are still restricted to ORG_ADMIN.
        return projectRepo.findByOrgId(orgId).stream()
                .map(ProjectDto::from)
                .collect(Collectors.toList());
    }

    public ProjectDto getProject(String projectId, String userId, boolean isSuperAdmin, boolean isOrgAdmin) {
        Project project = projectRepo.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        // All org members can view any project in their org (projects are a shared grouping tool).
        // Org membership is already validated by the controller's auth check.
        if (!isSuperAdmin) {
            AppUser user = userRepo.findById(userId).orElse(null);
            if (user == null || user.getOrgId() == null || !user.getOrgId().equals(project.getOrgId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this organization");
            }
        }
        return ProjectDto.from(project);
    }

    @Transactional
    public ProjectDto archiveProject(String projectId, String actorId) {
        Project project = projectRepo.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        String oldStatus = project.getStatus();
        project.setStatus("ARCHIVED");
        project.setUpdatedAt(Instant.now());
        ProjectDto result = ProjectDto.from(projectRepo.save(project));

        auditService.record(ActivityAudit.builder()
                .eventType("PROJECT_ARCHIVED")
                .entityType("PROJECT").entityId(projectId)
                .orgId(project.getOrgId()).actorId(actorId)
                .oldValue("{\"status\":\"" + oldStatus + "\"}")
                .newValue("{\"status\":\"ARCHIVED\"}")
                .build());

        return result;
    }

    @Transactional
    public void addMember(String projectId, String userId, String role, String actorId) {
        if (!projectMemberRepo.existsByProjectIdAndUserId(projectId, userId)) {
            String effectiveRole = role != null ? role : "PROJECT_DEVELOPER";
            projectMemberRepo.save(ProjectMember.builder()
                    .id(UUID.randomUUID().toString())
                    .projectId(projectId)
                    .userId(userId)
                    .projectRole(effectiveRole)
                    .build());

            Project project = projectRepo.findById(projectId).orElse(null);
            auditService.record(ActivityAudit.builder()
                    .eventType("PROJECT_MEMBER_ADDED")
                    .entityType("PROJECT").entityId(projectId)
                    .orgId(project != null ? project.getOrgId() : null).actorId(actorId)
                    .newValue("{\"userId\":\"" + userId + "\",\"role\":\"" + effectiveRole + "\"}")
                    .build());
        }
    }

    @Transactional
    public void removeMember(String projectId, String userId, String actorId) {
        if (!projectMemberRepo.existsByProjectIdAndUserId(projectId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found in project");
        }
        projectMemberRepo.deleteByProjectIdAndUserId(projectId, userId);

        Project project = projectRepo.findById(projectId).orElse(null);
        auditService.record(ActivityAudit.builder()
                .eventType("PROJECT_MEMBER_REMOVED")
                .entityType("PROJECT").entityId(projectId)
                .orgId(project != null ? project.getOrgId() : null).actorId(actorId)
                .oldValue("{\"userId\":\"" + userId + "\"}")
                .build());
    }

    @Transactional
    public ProjectDto updateSettings(String projectId, UpdateProjectSettingsRequest req, String actorId) {
        Project project = projectRepo.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        String oldProvider = project.getAiProvider() != null ? project.getAiProvider().name() : "ANTHROPIC";

        if (req.aiProvider() != null) {
            try {
                LlmProvider provider = LlmProvider.valueOf(req.aiProvider().toUpperCase());
                project.setAiProvider(provider);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid AI provider: " + req.aiProvider() + ". Valid: ANTHROPIC, GOOGLE, OPENAI");
            }
        }

        project.setUpdatedAt(Instant.now());
        ProjectDto result = ProjectDto.from(projectRepo.save(project));

        auditService.record(ActivityAudit.builder()
                .eventType("PROJECT_SETTINGS_UPDATED")
                .entityType("PROJECT").entityId(projectId)
                .orgId(project.getOrgId()).actorId(actorId)
                .oldValue("{\"aiProvider\":\"" + oldProvider + "\"}")
                .newValue("{\"aiProvider\":\"" + project.getAiProvider().name() + "\"}")
                .build());

        log.info("Project settings updated: {} — aiProvider: {} → {}", projectId, oldProvider, project.getAiProvider());
        return result;
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
