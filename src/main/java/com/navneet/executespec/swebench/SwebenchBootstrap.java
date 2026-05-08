package com.navneet.executespec.swebench;

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
 * Idempotently creates the synthetic SWE-bench fixture (Org + User + Project) on JVM startup when
 * {@code swebench.bootstrap=true}.
 *
 * <p>Mirrors {@link com.navneet.executespec.programbench.ProgramBenchBootstrap} but uses different
 * stable IDs so the SWE-bench Bench Project is a separate UI entity. This makes it easy to filter
 * SWE-bench runs vs ProgramBench runs in the dashboard.
 *
 * <p>Disabled by default. To enable on dev: {@code swebench.bootstrap=true}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SwebenchBootstrap {

  private final SwebenchProperties props;
  private final OrgRepository orgRepo;
  private final AppUserRepository userRepo;
  private final ProjectRepository projectRepo;

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void bootstrapOnStartup() {
    if (!props.isBootstrap()) {
      log.debug("SwebenchBootstrap skipped (swebench.bootstrap=false)");
      return;
    }

    String orgId = props.getBenchOrgId();
    String userId = props.getBenchUserId();
    String projectId = props.getBenchProjectId();

    boolean created = false;

    if (orgRepo.findById(orgId).isEmpty()) {
      Org org = new Org();
      org.setId(orgId);
      org.setName("SWE-bench Bench");
      org.setDomainPatterns("");
      org.setTier("ENTERPRISE");
      org.setStatus("ACTIVE");
      org.setSeatCount(1);
      org.setCreatedByUserId(userId);
      org.setCreatedAt(Instant.now());
      org.setUpdatedAt(Instant.now());
      orgRepo.save(org);
      log.info("SwebenchBootstrap: created Org {}", orgId);
      created = true;
    }

    if (userRepo.findById(userId).isEmpty()) {
      AppUser u = new AppUser();
      u.setId(userId);
      u.setEmail("swebench@bench.local");
      u.setFirstName("SWE-bench");
      u.setLastName("Bench");
      u.setPasswordHash("");
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
      log.info("SwebenchBootstrap: created User {}", userId);
      created = true;
    }

    if (projectRepo.findById(projectId).isEmpty()) {
      Project p = new Project();
      p.setId(projectId);
      p.setOrgId(orgId);
      p.setName("SWE-bench");
      p.setDescription(
          "Synthetic project — every SWE-bench task run is a child PipelineRun under this. Don't"
              + " edit manually; managed by SwebenchBootstrap.");
      p.setStatus("ACTIVE");
      p.setAiProvider(LlmProvider.ANTHROPIC);
      p.setCreatedBy(userId);
      p.setCreatedAt(Instant.now());
      p.setUpdatedAt(Instant.now());
      projectRepo.save(p);
      log.info("SwebenchBootstrap: created Project {}", projectId);
      created = true;
    }

    if (created) {
      log.info(
          "SwebenchBootstrap: ready — orgId={} userId={} projectId={}", orgId, userId, projectId);
    } else {
      log.debug("SwebenchBootstrap: fixture already present, no-op");
    }
  }
}
