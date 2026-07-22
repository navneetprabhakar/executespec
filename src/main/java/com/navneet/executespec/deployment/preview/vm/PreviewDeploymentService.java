package com.navneet.executespec.deployment.preview.vm;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.testgen.BoundedProcess;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Deploys generated projects to remote GCE VM(s) running Docker Compose behind Caddy. Backend is
 * the source of truth (preview_registry table); VM-local previews.json is a cache.
 *
 * <p>Multi-VM ready: load-balances across configured VMs, SCPs project to target VM, SSHs to run
 * deploy script, writes Caddy config on gateway VM if target is a worker.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "preview.mode", havingValue = "vm")
public class PreviewDeploymentService {

  private static final String PREVIEW_JSON_PREFIX = "PREVIEW_JSON:";
  private static final int SSH_CONNECT_TIMEOUT = 10;
  private static final int PROCESS_TIMEOUT_SECONDS = 300;

  private final PreviewVmProperties vmProperties;
  private final PreviewRegistryRepository registryRepo;
  private final PreviewMetricRepository metricsRepo;
  private final ObjectMapper objectMapper;

  /** Deploy a generated project as a live preview on a remote VM. */
  public PreviewVmResult deploy(String projectName, String executionId, Path projectDir) {
    String previewId = generatePreviewId(projectName);
    PreviewVmProperties.VmConfig targetVm = selectVm();
    int ttlMinutes = vmProperties.getDefaultTtlMinutes();

    PreviewRegistry entry =
        PreviewRegistry.builder()
            .id(previewId)
            .executionId(executionId)
            .projectName(projectName)
            .url("https://" + previewId + ".preview.executespec.ai")
            .vmId(targetVm.getId())
            .vmIp(targetVm.getInternalIp())
            .status("deploying")
            .healthy(false)
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES))
            .ttlHours(ttlMinutes) // field name is legacy; stores minutes now
            .build();
    registryRepo.save(entry);

    long startTime = System.currentTimeMillis();

    try {
      // SCP project to target VM
      String remoteTmpDir = "/tmp/preview-" + previewId;
      scpDirectory(projectDir, remoteTmpDir, targetVm.getExternalIp());

      // Deploy containers on target VM
      String output =
          sshExec(
              targetVm.getExternalIp(),
              String.format(
                  "/opt/preview-engine/bin/deploy-preview.sh %s %s %d",
                  previewId, remoteTmpDir, ttlMinutes));

      // Parse result from script output
      PreviewVmResult result = parsePreviewJson(output);

      // If target VM is NOT the gateway, write Caddy config on gateway
      if (!"gateway".equals(targetVm.getRole())) {
        String gatewayIp = vmProperties.getGateway().getIp();
        sshExec(
            gatewayIp,
            String.format(
                "/opt/preview-engine/bin/write-caddy-config.sh %s %s %d",
                previewId, targetVm.getInternalIp(), result.getPort()));
      }

      // Cleanup remote tmp
      sshExec(targetVm.getExternalIp(), "rm -rf " + remoteTmpDir);

      // Update registry
      int duration = (int) ((System.currentTimeMillis() - startTime) / 1000);
      entry.setStatus(result.isHealthy() ? "active" : "unhealthy");
      entry.setHealthy(result.isHealthy());
      entry.setPort(result.getPort());
      entry.setUrl(result.getUrl());
      entry.setDeployDurationS(duration);
      registryRepo.save(entry);

      recordMetric("deploy", previewId, targetVm.getId(), null);

      log.info("Preview deployed: {} on {} in {}s", result.getUrl(), targetVm.getId(), duration);
      return result;

    } catch (Exception e) {
      entry.setStatus("failed");
      entry.setErrorMessage(e.getMessage());
      registryRepo.save(entry);
      recordMetric("deploy_failure", previewId, targetVm.getId(), e.getMessage());
      throw new PreviewDeploymentException("Deploy failed: " + e.getMessage(), e);
    }
  }

  /** Tear down a preview by its ID. */
  public void teardown(String previewId) {
    int claimed = registryRepo.claimForTeardown(previewId);
    if (claimed != 1) {
      PreviewRegistry current =
          registryRepo
              .findById(previewId)
              .orElseThrow(() -> new PreviewDeploymentException("Preview not found: " + previewId));
      if (List.of("tearing_down", "gc_processing", "torn_down").contains(current.getStatus())) {
        log.info(
            "Preview teardown already claimed or complete: {} status={}",
            previewId,
            current.getStatus());
        return;
      }
      throw new PreviewDeploymentException(
          "Preview cannot be torn down from status " + current.getStatus() + ": " + previewId);
    }
    teardownClaimed(previewId);
  }

  /** Execute teardown after the caller atomically claimed the registry row. */
  void teardownClaimed(String previewId) {
    PreviewRegistry entry =
        registryRepo
            .findById(previewId)
            .orElseThrow(() -> new PreviewDeploymentException("Preview not found: " + previewId));

    String vmExternalIp = resolveExternalIp(entry.getVmId());

    // Teardown containers on the VM
    sshExec(vmExternalIp, "/opt/preview-engine/bin/teardown-preview.sh " + previewId);

    // If VM is not gateway, also remove Caddy config from gateway
    if (!vmProperties.getGateway().getVmId().equals(entry.getVmId())) {
      sshExec(
          vmProperties.getGateway().getIp(),
          String.format(
              "rm -f /opt/preview-engine/caddy/sites/%s.caddy && "
                  + "docker exec caddy-proxy caddy reload --config /etc/caddy/Caddyfile",
              previewId));
    }

    entry.setStatus("torn_down");
    entry.setTornDownAt(Instant.now());
    registryRepo.save(entry);

    recordMetric("teardown", previewId, entry.getVmId(), null);
    log.info("Preview torn down: {}", previewId);
  }

  /** Extend a preview's TTL. */
  public void extend(String previewId, int extraMinutes) {
    PreviewRegistry entry =
        registryRepo
            .findById(previewId)
            .orElseThrow(() -> new PreviewDeploymentException("Preview not found: " + previewId));

    String vmExternalIp = resolveExternalIp(entry.getVmId());

    sshExec(
        vmExternalIp,
        String.format("/opt/preview-engine/bin/extend-preview.sh %s %d", previewId, extraMinutes));

    entry.setExpiresAt(Instant.now().plus(extraMinutes, ChronoUnit.MINUTES));
    registryRepo.save(entry);

    log.info("Preview {} extended by {}m", previewId, extraMinutes);
  }

  /**
   * Returns true if at least one VM has a free slot. Used by PreviewService to check capacity
   * before spawning a build thread, so requests can be queued gracefully.
   */
  public boolean hasCapacity() {
    List<PreviewVmProperties.VmConfig> vms = vmProperties.getVms();
    if (vms.isEmpty()) return false;
    Map<String, Long> loadMap =
        registryRepo.findByStatusIn(List.of("active", "deploying", "unhealthy")).stream()
            .collect(Collectors.groupingBy(PreviewRegistry::getVmId, Collectors.counting()));
    return vms.stream()
        .anyMatch(vm -> loadMap.getOrDefault(vm.getId(), 0L) < vm.getMaxConcurrent());
  }

  /** Select the VM with the lowest active preview count that has capacity. */
  private PreviewVmProperties.VmConfig selectVm() {
    List<PreviewVmProperties.VmConfig> vms = vmProperties.getVms();
    if (vms.isEmpty()) {
      throw new PreviewDeploymentException("No preview VMs configured");
    }

    Map<String, Long> loadMap =
        registryRepo.findByStatusIn(List.of("active", "deploying", "unhealthy")).stream()
            .collect(Collectors.groupingBy(PreviewRegistry::getVmId, Collectors.counting()));

    return vms.stream()
        .filter(vm -> loadMap.getOrDefault(vm.getId(), 0L) < vm.getMaxConcurrent())
        .min(Comparator.comparingLong(vm -> loadMap.getOrDefault(vm.getId(), 0L)))
        .orElseThrow(() -> new PreviewDeploymentException("All preview VMs at capacity"));
  }

  // RR-1618 — package-private static so the entropy/format is directly unit-testable; uses no
  // instance state.
  static String generatePreviewId(String projectName) {
    String slug =
        projectName
            .toLowerCase()
            .replaceAll("[^a-z0-9]", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
    // RR-1618: the id is the registry @Id (a merge, not an insert), the DNS label, the Caddy
    // site-config filename and the remote /tmp dir. The prior 4-hex suffix carried only 16 bits of
    // entropy over a caller-influenced project slug, so a same-named project collided with
    // p≈1/65536
    // per pair — and an attacker who can name a project could retry until a match and silently
    // overwrite the victim's registry row. Use the full 128-bit UUID (32 hex chars). Cap the slug
    // so
    // the whole id stays within the 63-char DNS label limit (24 + '-' + 32 = 57).
    if (slug.length() > 24) {
      slug = slug.substring(0, 24).replaceAll("-$", "");
    }
    String hash = UUID.randomUUID().toString().replace("-", "");
    return slug.isEmpty() ? hash : slug + "-" + hash;
  }

  private void scpDirectory(Path localDir, String remotePath, String targetIp) {
    String sshUser = vmProperties.getGateway().getUser();
    String sshKeyPath = vmProperties.getGateway().getSshKeyPath();

    execute(
        new ProcessBuilder(
            "scp",
            "-r",
            "-i",
            sshKeyPath,
            "-o",
            // Audit 2026-05-12 review item I #55: StrictHostKeyChecking=no is a MITM vector.
            // Use "accept-new" so first contact pins the host key into ~/.ssh/known_hosts and
            // every subsequent connection requires a matching key. Operators provisioning the
            // preview-gw VM can also pre-populate known_hosts to remove first-connect risk.
            "StrictHostKeyChecking=accept-new",
            "-o",
            "ConnectTimeout=" + SSH_CONNECT_TIMEOUT,
            localDir.toString(),
            String.format("%s@%s:%s", sshUser, targetIp, remotePath)));
  }

  private String sshExec(String targetIp, String command) {
    String sshUser = vmProperties.getGateway().getUser();
    String sshKeyPath = vmProperties.getGateway().getSshKeyPath();

    return execute(
        new ProcessBuilder(
            "ssh",
            "-i",
            sshKeyPath,
            "-o",
            // Audit 2026-05-12 review item I #55: StrictHostKeyChecking=no is a MITM vector.
            // Use "accept-new" so first contact pins the host key into ~/.ssh/known_hosts and
            // every subsequent connection requires a matching key. Operators provisioning the
            // preview-gw VM can also pre-populate known_hosts to remove first-connect risk.
            "StrictHostKeyChecking=accept-new",
            "-o",
            "ConnectTimeout=" + SSH_CONNECT_TIMEOUT,
            String.format("%s@%s", sshUser, targetIp),
            command));
  }

  private String execute(ProcessBuilder pb) {
    try {
      pb.redirectErrorStream(true);
      // RR-1037: BoundedProcess drains the SSH/SCP child on a separate thread so the deadline is
      // reachable while the child is still alive, and caps captured output. Reading stdout to EOF
      // first (the old shape) let a hung child that kept its pipe open defeat the timeout, while an
      // unbounded read could exhaust the heap.
      BoundedProcess.Outcome outcome =
          BoundedProcess.run(pb, Duration.ofSeconds(PROCESS_TIMEOUT_SECONDS));
      if (!outcome.finished()) {
        throw new PreviewDeploymentException(
            "Command timed out after " + PROCESS_TIMEOUT_SECONDS + "s");
      }
      if (outcome.exitCode() != 0) {
        throw new PreviewDeploymentException(
            "Command failed (exit "
                + outcome.exitCode()
                + "): "
                + truncate(outcome.output(), 2000));
      }
      return outcome.output();
    } catch (PreviewDeploymentException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new PreviewDeploymentException("Execution failed: " + e.getMessage(), e);
    }
  }

  private PreviewVmResult parsePreviewJson(String output) {
    String jsonLine =
        Arrays.stream(output.split("\n"))
            .filter(l -> l.startsWith(PREVIEW_JSON_PREFIX))
            .findFirst()
            .map(l -> l.substring(PREVIEW_JSON_PREFIX.length()).trim())
            .orElseThrow(() -> new PreviewDeploymentException("No PREVIEW_JSON in deploy output"));
    try {
      return objectMapper.readValue(jsonLine, PreviewVmResult.class);
    } catch (JsonProcessingException e) {
      throw new PreviewDeploymentException("Failed to parse preview JSON: " + jsonLine, e);
    }
  }

  private String resolveExternalIp(String vmId) {
    return vmProperties.getVms().stream()
        .filter(vm -> vm.getId().equals(vmId))
        .findFirst()
        .map(PreviewVmProperties.VmConfig::getExternalIp)
        .orElseThrow(() -> new PreviewDeploymentException("Unknown VM: " + vmId));
  }

  private void recordMetric(String eventType, String previewId, String vmId, String message) {
    PreviewMetric metric =
        PreviewMetric.builder()
            .eventType(eventType)
            .previewId(previewId)
            .vmId(vmId)
            .message(message)
            .createdAt(Instant.now())
            .build();
    metricsRepo.save(metric);
  }

  private static String truncate(String s, int maxLen) {
    return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
  }
}
