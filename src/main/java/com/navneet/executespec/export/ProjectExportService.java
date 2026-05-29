package com.navneet.executespec.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.activity.ActivityFeedEntry;
import com.navneet.executespec.activity.ActivityFeedEntryRepository;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * E4.7 — composes a portable, structured snapshot of a project per plan §4.
 *
 * <h2>Bundle layout</h2>
 *
 * <pre>
 *   manifest.json
 *   project.json
 *   members.json
 *   contracts/index.json
 *   activity/events-YYYY-MM.ndjson
 *   README.md
 * </pre>
 *
 * <p>Workspace + per-repo .executespec/ content is deliberately NOT duplicated in the bundle
 * (manifest points to the linked repos — customer git is authoritative). Memory ledger + per-run
 * JSON are deferred to a follow-up to keep this slice bounded; the bundle shape is
 * forward-compatible (manifest schema_version=1).
 *
 * <p>Output is a {@code byte[]} tar.gz suitable for streaming to the response or to GCS.
 * Synchronous in v1; async job-tracking lands in a follow-up.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectExportService {

  static final int SCHEMA_VERSION = 1;

  private final ProjectRepository projectRepo;
  private final ProjectRepoLinkRepository linkRepo;
  private final ProjectRepoRepository projectRepoRepo;
  private final ContractRegistryRepository contractRepo;
  private final ActivityFeedEntryRepository feedRepo;
  private final ObjectMapper mapper;

  public byte[] composeBundle(String projectId) throws IOException {
    Project project = projectRepo.findById(projectId).orElse(null);
    if (project == null) {
      throw new IllegalArgumentException("project not found: " + projectId);
    }
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(baos);
        TarOutputStream tar = new TarOutputStream(gz)) {

      // 1. manifest.json — schema version + timestamp
      tar.putFile(
          "manifest.json",
          mapper.writeValueAsBytes(
              Map.of(
                  "schemaVersion",
                  SCHEMA_VERSION,
                  "generatedAt",
                  Instant.now().toString(),
                  "projectId",
                  projectId,
                  "format",
                  "tar.gz")));

      // 2. project.json — core project metadata (no secrets).
      // Map.of forbids null values, so coalesce every field defensively.
      tar.putFile(
          "project.json",
          mapper.writeValueAsBytes(
              Map.of(
                  "id", nv(project.getId()),
                  "name", nv(project.getName()),
                  "orgId", nv(project.getOrgId()),
                  "aiProvider",
                      project.getAiProvider() == null ? "" : project.getAiProvider().name(),
                  "createdBy", nv(project.getCreatedBy()),
                  "createdAt",
                      project.getCreatedAt() == null ? "" : project.getCreatedAt().toString())));

      // 3. members.json — placeholder (members table membership lookup
      // is project-membership-service shaped; ship the link list as
      // proxy in v1 to keep the slice bounded — full member rollup is
      // a follow-up)
      tar.putFile(
          "members.json",
          mapper.writeValueAsBytes(
              Map.of(
                  "note",
                  "Member rollup deferred; "
                      + "see linked repos as proxy via contracts/index.json")));

      // 4. contracts/index.json — cross-repo contract registry
      List<Map<String, Object>> contracts = new ArrayList<>();
      for (ProjectRepoLink link : linkRepo.findByProjectId(projectId)) {
        for (ContractRegistry c : contractRepo.findByRepoId(link.getRepoId())) {
          contracts.add(
              Map.of(
                  "id", nv(c.getId() == null ? null : c.getId().toString()),
                  "name", nv(c.getName()),
                  "type", c.typeEnum().name(),
                  "version",
                      c.getCurrentRegistryVersion() == null ? 0 : c.getCurrentRegistryVersion(),
                  "contentHash", nv(c.getCurrentContentHash()),
                  "detectionMethod", c.detectionMethodEnum().name(),
                  "repoId", nv(c.getRepoId() == null ? null : c.getRepoId().toString()),
                  "repoFullName",
                      projectRepoRepo
                          .findById(c.getRepoId())
                          .map(r -> r.getGithubRepoFullName())
                          .orElse("(unknown)"),
                  "detectedPath", c.getDetectedPath() == null ? "" : c.getDetectedPath()));
        }
      }
      tar.putFile(
          "contracts/index.json",
          mapper.writeValueAsBytes(Map.of("count", contracts.size(), "contracts", contracts)));

      // 5. activity/events-<window>.ndjson — last 90 days
      try {
        java.util.UUID projectUuid = java.util.UUID.fromString(projectId);
        Instant from = Instant.now().minus(90, ChronoUnit.DAYS);
        Instant to = Instant.now().plus(1, ChronoUnit.DAYS);
        List<ActivityFeedEntry> events = feedRepo.findRecentForProject(projectUuid, from, to);
        StringBuilder ndjson = new StringBuilder();
        for (ActivityFeedEntry e : events) {
          ndjson.append(
              mapper.writeValueAsString(
                  Map.of(
                      "id", nv(e.getId() == null ? null : e.getId().toString()),
                      "occurredAt",
                          nv(e.getOccurredAt() == null ? null : e.getOccurredAt().toString()),
                      "eventType", nv(e.getEventType()),
                      "actorType", nv(e.getActorType()),
                      "actorUserId",
                          e.getActorUserId() == null ? "" : e.getActorUserId().toString(),
                      "targetType", nv(e.getTargetType()),
                      "targetId", nv(e.getTargetId()),
                      "summary", nv(e.getSummary()),
                      "schemaVersion", e.getSchemaVersion() == null ? 1 : e.getSchemaVersion())));
          ndjson.append('\n');
        }
        tar.putFile(
            "activity/events-recent.ndjson", ndjson.toString().getBytes(StandardCharsets.UTF_8));
      } catch (IllegalArgumentException ex) {
        // Non-UUID projectId — feed has no events for it; emit empty file
        tar.putFile("activity/events-recent.ndjson", new byte[0]);
      }

      // 6. README.md — human starting point per plan §4.2
      tar.putFile(
          "README.md", composeReadme(project, contracts.size()).getBytes(StandardCharsets.UTF_8));
    }
    return baos.toByteArray();
  }

  /** Null-coalesce to empty string — Map.of forbids null values. */
  static String nv(String s) {
    return s == null ? "" : s;
  }

  String composeReadme(Project project, int contractCount) {
    return """
           # ExecuteSpec project export

           **Project:** %s
           **Project id:** %s
           **Generated:** %s
           **Schema version:** %d

           ## Layout

           - `manifest.json` — bundle metadata + schema version (rehydration root)
           - `project.json` — project record (no secrets)
           - `members.json` — member roster (auth-data-free placeholder in v1)
           - `contracts/index.json` — %d contract(s) detected/declared in linked repos
           - `activity/events-recent.ndjson` — last 90 days of activity feed
             (one NDJSON event per line; structured payload preserved)

           ## What's NOT in this bundle

           Per ExecuteSpec's git-authoritative model, the per-repo
           `.executespec/` content stays in customer git — not duplicated
           here. The contract index points to the producing repos so you
           can cross-reference.

           Memory ledger + per-run JSON snapshots are deferred to a
           future bundle version (manifest.schemaVersion = %d today;
           consumers should branch on the version field for forward
           compatibility).
           """
        .formatted(
            project.getName(),
            project.getId(),
            Instant.now().toString(),
            SCHEMA_VERSION,
            contractCount,
            SCHEMA_VERSION);
  }

  // Tiny tar writer (USTAR; no external deps)

  /**
   * Minimal POSIX USTAR writer. Sufficient for our file-set (text + JSON, never multi-MB). Avoids
   * pulling commons-compress just for a 6-file tar archive.
   */
  static class TarOutputStream implements AutoCloseable {
    private final OutputStream out;

    TarOutputStream(OutputStream out) {
      this.out = out;
    }

    void putFile(String path, byte[] bytes) throws IOException {
      byte[] header = new byte[512];
      // name (100 bytes)
      byte[] nameBytes = path.getBytes(StandardCharsets.UTF_8);
      System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));
      // mode (8 bytes octal)
      writeOctal(header, 100, 8, 0644);
      // uid + gid
      writeOctal(header, 108, 8, 0);
      writeOctal(header, 116, 8, 0);
      // size (12 bytes octal)
      writeOctal(header, 124, 12, bytes.length);
      // mtime (12 bytes octal)
      writeOctal(header, 136, 12, Instant.now().getEpochSecond());
      // checksum placeholder (8 spaces)
      for (int i = 148; i < 156; i++) header[i] = ' ';
      // typeflag '0' = regular file
      header[156] = '0';
      // magic "ustar"
      byte[] magic = "ustar\0".getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(magic, 0, header, 257, magic.length);
      // version "00"
      header[263] = '0';
      header[264] = '0';
      // checksum
      int sum = 0;
      for (byte b : header) sum += (b & 0xff);
      String checkOctal = String.format("%06o", sum) + "\0 ";
      byte[] checkBytes = checkOctal.getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(checkBytes, 0, header, 148, checkBytes.length);

      out.write(header);
      out.write(bytes);
      // pad to 512-byte boundary
      int rem = bytes.length % 512;
      if (rem > 0) out.write(new byte[512 - rem]);
    }

    private static void writeOctal(byte[] header, int offset, int len, long value) {
      String s = String.format("%0" + (len - 1) + "o", value);
      byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    @Override
    public void close() throws IOException {
      // Two empty 512-byte blocks terminate the archive
      out.write(new byte[1024]);
      out.flush();
    }
  }
}
