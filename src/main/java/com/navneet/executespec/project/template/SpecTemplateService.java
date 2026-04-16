package com.navneet.executespec.project.template;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * v2.5.2 — CRUD for {@link ProjectSpecTemplate}. Project list merges
 * project-scoped + org-scoped templates so the UX-2.3 picker shows both
 * sources with one call.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpecTemplateService {

    private final ProjectSpecTemplateRepository repo;
    private final ProjectRepository projectRepo;

    public record CreateRequest(String name, String description, String requirementTemplate) {}

    public record TemplateDto(
            String id,
            String ownerType,
            String ownerId,
            String name,
            String description,
            String requirementTemplate,
            Instant createdAt) {
        public static TemplateDto from(ProjectSpecTemplate t) {
            return new TemplateDto(
                    t.getId().toString(),
                    t.getOwnerType().name(),
                    t.getOwnerId(),
                    t.getName(),
                    t.getDescription(),
                    t.getRequirementTemplate(),
                    t.getCreatedAt());
        }
    }

    /** Project-scoped list; auto-includes any org templates the project belongs to. */
    public List<TemplateDto> listForProject(String projectId, AppUser caller) {
        Project project = requireProjectAccess(projectId, caller);
        List<TemplateDto> out = new ArrayList<>();
        repo.findByOwnerTypeAndOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                ProjectSpecTemplate.OwnerType.PROJECT, projectId)
                .forEach(t -> out.add(TemplateDto.from(t)));
        if (project.getOrgId() != null) {
            repo.findByOwnerTypeAndOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                    ProjectSpecTemplate.OwnerType.ORG, project.getOrgId())
                    .forEach(t -> out.add(TemplateDto.from(t)));
        }
        return out;
    }

    @Transactional
    public TemplateDto createForProject(String projectId, AppUser caller, CreateRequest req) {
        requireProjectAccess(projectId, caller);
        validate(req);
        ProjectSpecTemplate t = ProjectSpecTemplate.builder()
                .ownerType(ProjectSpecTemplate.OwnerType.PROJECT)
                .ownerId(projectId)
                .name(req.name().trim())
                .description(req.description() == null ? null : req.description().trim())
                .requirementTemplate(req.requirementTemplate())
                .createdBy(caller.getId())
                .build();
        return TemplateDto.from(repo.save(t));
    }

    @Transactional
    public TemplateDto createForOrg(String orgId, AppUser caller, CreateRequest req) {
        if (orgId == null || !orgId.equals(caller.getOrgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Caller does not belong to org " + orgId);
        }
        validate(req);
        ProjectSpecTemplate t = ProjectSpecTemplate.builder()
                .ownerType(ProjectSpecTemplate.OwnerType.ORG)
                .ownerId(orgId)
                .name(req.name().trim())
                .description(req.description() == null ? null : req.description().trim())
                .requirementTemplate(req.requirementTemplate())
                .createdBy(caller.getId())
                .build();
        return TemplateDto.from(repo.save(t));
    }

    @Transactional
    public void delete(String templateId, AppUser caller) {
        ProjectSpecTemplate t = repo.findById(UUID.fromString(templateId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found"));
        // Authorization: PROJECT-scoped → project access. ORG-scoped → org match.
        if (t.getOwnerType() == ProjectSpecTemplate.OwnerType.PROJECT) {
            requireProjectAccess(t.getOwnerId(), caller);
        } else if (!t.getOwnerId().equals(caller.getOrgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete this template");
        }
        t.setDeletedAt(Instant.now());
        repo.save(t);
    }

    private void validate(CreateRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name required");
        }
        if (req.requirementTemplate() == null || req.requirementTemplate().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requirementTemplate required");
        }
        if (req.name().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name too long (max 255)");
        }
    }

    private Project requireProjectAccess(String projectId, AppUser caller) {
        Project project = projectRepo.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        // Same access model as other project-scoped controllers: caller's org
        // must match the project's org. Per-user PROJECT_DEVELOPER scoping is
        // enforced upstream by the security filter; here we trust the principal.
        if (project.getOrgId() != null && !project.getOrgId().equals(caller.getOrgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project not visible to caller");
        }
        return project;
    }
}
