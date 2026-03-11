package com.navneet.executespec.auth.service;

import com.navneet.executespec.auth.dto.AuditLogEntry;
import com.navneet.executespec.auth.dto.AuditLogPage;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.repository.ActivityAuditRepository;
import com.navneet.executespec.auth.repository.ActivityAuditSpec;
import com.navneet.executespec.auth.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityAuditService {

    private final ActivityAuditRepository repo;
    private final AppUserRepository userRepo;

    /**
     * Record an audit event. Assigns a UUID if the entry has no id.
     */
    public void record(ActivityAudit entry) {
        if (entry.getId() == null) {
            entry.setId(UUID.randomUUID().toString());
        }
        if (entry.getCreatedAt() == null) {
            entry.setCreatedAt(Instant.now());
        }
        repo.save(entry);
        log.info("Audit: {} {} {} (actor={})", entry.getEventType(),
                entry.getEntityType(), entry.getEntityId(), entry.getActorId());
    }

    /**
     * Query audit logs with filters. Returns paginated results with actor email resolved.
     */
    public AuditLogPage query(
            String orgId,
            String entityType,
            String eventType,
            String actorId,
            String entityId,
            Instant from,
            Instant to,
            int page,
            int size) {

        Specification<ActivityAudit> spec = ActivityAuditSpec.buildFilter(
                orgId, entityType, eventType, actorId, entityId, from, to);

        return AuditLogPage.from(
                repo.findAll(spec, PageRequest.of(page, Math.min(size, 100)))
                        .map(this::toEntry));
    }

    private AuditLogEntry toEntry(ActivityAudit a) {
        String actorEmail = null;
        if (a.getActorId() != null) {
            actorEmail = userRepo.findById(a.getActorId())
                    .map(u -> u.getEmail())
                    .orElse(null);
        }
        return new AuditLogEntry(
                a.getId(),
                a.getEventType(),
                a.getEntityType(),
                a.getEntityId(),
                a.getOrgId(),
                a.getActorId(),
                actorEmail,
                a.getOldValue(),
                a.getNewValue(),
                a.getMetadata(),
                a.getCreatedAt()
        );
    }
}
