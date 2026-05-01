package com.navneet.executespec.deployment.preview.engine;

import java.nio.file.Path;
import java.util.Map;

import com.navneet.executespec.deployment.preview.PreviewProjectType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Orchestrates the "build → push → hand off to preview-engine" flow.
 *
 * <p>Eligibility rule (Phase-1 scope):
 *
 * <ul>
 *   <li>Single-service projects only — no compose-declared Postgres/Redis. Multi-service support
 *       lands later once preview-engine's from-image endpoint accepts sidecar specs alongside the
 *       main image.
 *   <li>Feature flag {@code preview.engine.route-via} must be true.
 *   <li>Preview-engine service must be reachable (fail-fast check).
 * </ul>
 */
@Component
public class PreviewEngineHandoff {

  private static final Logger log = LoggerFactory.getLogger(PreviewEngineHandoff.class);

  private final PreviewImagePusher pusher;
  private final PreviewEngineClient client;

  public PreviewEngineHandoff(PreviewImagePusher pusher, PreviewEngineClient client) {
    this.pusher = pusher;
    this.client = client;
  }

  public record HandoffResult(
      String previewId, String previewUrl, String imageRef, boolean skippedBuild) {}

  /**
   * Build the preview image, push to the registry, call preview-engine's /from-image. Blocks until
   * engine reports READY or fails.
   *
   * @param runId main app run id
   * @param workspace workspace root (must contain Dockerfile)
   * @param dockerfile path to the generated Dockerfile
   * @param type detected project type — drives combo + internal port
   * @param ownerUserId caller identity for tier-cap enforcement
   * @param ownerTier caller tier (FREE / PRO / MAX / ENTERPRISE)
   * @param ttlSeconds requested TTL
   * @param envVars env vars to inject into the preview container
   */
  public HandoffResult start(
      String runId,
      Path workspace,
      Path dockerfile,
      PreviewProjectType type,
      String ownerUserId,
      String ownerTier,
      int ttlSeconds,
      Map<String, String> envVars) {

    if (!client.isEnabled()) {
      throw new IllegalStateException("preview-engine routing is disabled");
    }

    PreviewImagePusher.PushResult pushed =
        pusher.buildAndPush(client.registryUrl(), runId, workspace, dockerfile);

    log.info(
        "[{}] handing off to preview-engine ({} build)",
        runId,
        pushed.skippedBuild() ? "skipped" : "fresh");

    PreviewEngineClient.EngineResponse resp =
        client.registerFromImage(
            pushed.imageRef(),
            runId,
            comboFor(type),
            ownerUserId,
            ownerTier,
            internalPortFor(type),
            healthPathFor(type),
            ttlSeconds,
            envVars);

    if (!"READY".equals(resp.status())) {
      throw new PreviewEngineClient.PreviewEngineException(
          "preview-engine reported status=" + resp.status() + " reason=" + resp.statusReason(),
          null);
    }

    log.info(
        "[{}] preview-engine READY — previewId={} url={}", runId, resp.id(), resp.previewUrl());

    return new HandoffResult(
        resp.id(), resp.previewUrl(), pushed.imageRef(), pushed.skippedBuild());
  }

  public void stop(String previewId) {
    if (previewId == null || previewId.isBlank()) return;
    client.unregister(previewId);
  }

  // ───────────────────────── combo mapping ─────────────────────────

  private static String comboFor(PreviewProjectType type) {
    return switch (type) {
      case NODE_FRONTEND -> "node-frontend";
      case NODE_BACKEND -> "node-backend";
      case FULL_STACK -> "node-fullstack";
      case PYTHON -> "python-backend";
      case GO -> "go-backend";
      case RUST -> "rust-backend";
      case JAVA -> "java-backend";
      case SPRING_BOOT -> "java-spring-boot";
      case STATIC_HTML -> "static-web";
      case REACT_NATIVE_EXPO -> "mobile-expo-web";
    };
  }

  /** Internal listening port — delegates to the enum's canonical port map. */
  private static int internalPortFor(PreviewProjectType type) {
    return type.getContainerPort();
  }

  private static String healthPathFor(PreviewProjectType type) {
    // Generic root probe works for everything the DevDockerfileGenerator builds.
    // Specific health endpoints (/health, /actuator/health) are reachable afterwards.
    return "/";
  }
}
