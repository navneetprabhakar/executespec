package com.navneet.executespec.programbench;

import java.time.Instant;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.router.LlmProvider;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Idempotently creates the synthetic ProgramBench fixture (Org + User + Project) on JVM startup
 * when {@code programbench.bootstrap=true}.
 *
 * <p>Each ProgramBench task run is attributed to this fixture so:
 *
 * <ul>
 *   <li>The PipelineRun + AgentTask rows are real (visible in the UI under this Project)
 *   <li>Existing {@code RunOrchestrator.submitRun(...)} works without the synthetic params
 *       requiring DB-write privileges at run time
 *   <li>Quotas / billing checks pass (the fixture user has admin tier)
 * </ul>
 *
 * <p>Disabled by default. To enable on dev: {@code programbench.bootstrap=true}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProgramBenchBootstrap {

  private final ProgramBenchProperties props;
  private final OrgRepository orgRepo;
  private final AppUserRepository userRepo;
  private final ProjectRepository projectRepo;

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void bootstrapOnStartup() {
    if (!props.isBootstrap()) {
      log.debug("ProgramBenchBootstrap skipped (programbench.bootstrap=false)");
      return;
    }

    String orgId = props.getBenchOrgId();
    String userId = props.getBenchUserId();
    String projectId = props.getBenchProjectId();

    boolean created = false;

    if (orgRepo.findById(orgId).isEmpty()) {
      Org org = new Org();
      org.setId(orgId);
      org.setName("ProgramBench Bench");
      org.setDomainPatterns(
          ""); // NOT NULL on the column; no domain enforcement for the bench fixture
      org.setTier("ENTERPRISE");
      org.setStatus("ACTIVE");
      org.setSeatCount(1);
      org.setCreatedByUserId(userId); // soft string ref — User row created next; no FK constraint
      org.setCreatedAt(Instant.now());
      org.setUpdatedAt(Instant.now());
      orgRepo.save(org);
      log.info("ProgramBenchBootstrap: created Org {}", orgId);
      created = true;
    }

    if (userRepo.findById(userId).isEmpty()) {
      AppUser u = new AppUser();
      u.setId(userId);
      u.setEmail("programbench@bench.local");
      u.setFirstName("ProgramBench");
      u.setLastName("Bench");
      u.setPasswordHash(""); // login-disabled — only used as a foreign-key target
      u.setGlobalRole("SUPER_ADMIN");
      u.setTier("ENTERPRISE");
      u.setStatus("ACTIVE");
      u.setEmailVerified(true);
      u.setOrgId(orgId);
      u.setLifetimeRunCreditBalance(0);
      u.setLifetimeRunCreditCap(0);
      u.setCreatedAt(Instant.now());
      u.setUpdatedAt(Instant.now());
      userRepo.save(u);
      log.info("ProgramBenchBootstrap: created User {}", userId);
      created = true;
    }

    if (projectRepo.findById(projectId).isEmpty()) {
      Project p = new Project();
      p.setId(projectId);
      p.setOrgId(orgId);
      p.setName("ProgramBench");
      p.setDescription(
          "Synthetic project — every ProgramBench task run is a child PipelineRun under this. Don't"
              + " edit manually; managed by ProgramBenchBootstrap.");
      p.setStatus("ACTIVE");
      p.setAiProvider(LlmProvider.ANTHROPIC);
      p.setCreatedBy(userId);
      p.setCreatedAt(Instant.now());
      p.setUpdatedAt(Instant.now());
      // is_sandbox defaults to false on the entity; no setter needed.
      projectRepo.save(p);
      log.info("ProgramBenchBootstrap: created Project {}", projectId);
      created = true;
    }

    if (created) {
      log.info(
          "ProgramBenchBootstrap: ready — orgId={} userId={} projectId={}",
          orgId,
          userId,
          projectId);
    } else {
      log.debug("ProgramBenchBootstrap: fixture already present, no-op");
    }
  }
}
