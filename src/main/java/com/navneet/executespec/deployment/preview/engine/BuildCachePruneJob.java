package com.navneet.executespec.deployment.preview.engine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.deployment.preview.PreviewProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the main app's local BuildKit layer cache bounded.
 *
 * <p>Every 6 h (configurable), runs {@code docker builder prune --keep-storage 10g} to evict the
 * least-recently-used layers when the cache exceeds 10 GB. Also prunes preview-engine-labelled
 * local images older than 24 h so stale tags don't linger on builder VMs.
 *
 * <p>No-op when {@code preview.engine.route-via=false} (we aren't building preview-engine-bound
 * images, so no cache to manage).
 */
@Component
public class BuildCachePruneJob {

  private static final Logger log = LoggerFactory.getLogger(BuildCachePruneJob.class);

  private final PreviewProperties props;
  private final String keepStorage;
  private final String imageUntil;

  public BuildCachePruneJob(
      PreviewProperties props,
      @Value("${preview.engine.build-cache.keep-storage:10g}") String keepStorage,
      @Value("${preview.engine.build-cache.image-until:24h}") String imageUntil) {
    this.props = props;
    this.keepStorage = keepStorage;
    this.imageUntil = imageUntil;
  }

  /** Runs every 6 h — see {@code preview.engine.build-cache.cron-millis}. */
  @Scheduled(
      fixedDelayString = "${preview.engine.build-cache.cron-millis:21600000}",
      initialDelay = 30 * 60_000)
  public void prune() {
    if (!props.getEngine().isRouteVia()) return;

    String cacheOut =
        exec(List.of("docker", "builder", "prune", "-f", "--keep-storage", keepStorage));
    if (cacheOut != null && cacheOut.contains("Total:")) {
      log.info(
          "buildkit cache prune (keep-storage={}): {}",
          keepStorage,
          cacheOut.replaceAll("\\s+", " ").trim());
    }

    String imgOut =
        exec(
            List.of(
                "docker",
                "image",
                "prune",
                "-f",
                "--filter",
                "label=executespec.preview-run=true",
                "--filter",
                "until=" + imageUntil));
    if (imgOut != null && imgOut.contains("Total reclaimed space:") && !imgOut.contains("0B")) {
      log.info(
          "preview-run image prune (until={}): {}",
          imageUntil,
          imgOut.replaceAll("\\s+", " ").trim());
    }
  }

  // ────────────────────────────────────────────────────────────────

  private String exec(List<String> cmd) {
    try {
      ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
      Process p = pb.start();
      StringBuilder sb = new StringBuilder();
      try (BufferedReader br =
          new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
      }
      if (!p.waitFor(120, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return null;
      }
      return sb.toString();
    } catch (Exception e) {
      log.warn("build-cache prune failed: {}", e.getMessage());
      return null;
    }
  }
}
