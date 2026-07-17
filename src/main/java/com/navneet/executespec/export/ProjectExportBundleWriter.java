package com.navneet.executespec.export;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.activity.ActivityFeedEntry;
import com.navneet.executespec.activity.ActivityFeedEntryRepository;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.security.ContextSanitizer;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Bounded-memory schema-v2 project archive composer. Large JSON/NDJSON members are first spooled to
 * bounded private files, then copied through gzip/tar; the final archive never exists as a byte
 * array.
 */
@Component
@RequiredArgsConstructor
public class ProjectExportBundleWriter {

  static final int SCHEMA_VERSION = 2;
  private static final int MAX_SUMMARY_CHARS = 4000;

  private final ProjectRepository projectRepo;
  private final ProjectMemberRepository memberRepo;
  private final ProjectRepoLinkRepository linkRepo;
  private final ProjectRepoRepository repoRepo;
  private final ContractRegistryRepository contractRepo;
  private final ActivityFeedEntryRepository feedRepo;
  private final ObjectMapper mapper;
  private final ContextSanitizer sanitizer;
  private final ProjectExportProperties properties;

  @FunctionalInterface
  public interface Checkpoint {
    void check();
  }

  public record Result(
      int repositories,
      int contracts,
      int activityEvents,
      boolean repositoriesTruncated,
      boolean contractsTruncated,
      boolean activityTruncated,
      long uncompressedBytes) {}

  public Result write(ProjectExportJobStore.Job job, Path output, Checkpoint checkpoint)
      throws IOException {
    Project project =
        projectRepo
            .findById(job.projectId())
            .orElseThrow(() -> new IllegalArgumentException("project not found"));
    if (!job.orgId().equals(project.getOrgId())) {
      throw new IllegalStateException("project tenant changed during export");
    }

    Path spool = Files.createTempDirectory(output.getParent(), "project-export-spool-");
    try {
      checkpoint.check();
      ContractStats contractStats = writeContracts(job.projectId(), spool, checkpoint);
      ActivityStats activityStats = writeActivity(job, spool, checkpoint);
      checkpoint.check();

      long uncompressed;
      try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(output));
          GZIPOutputStream gzip = new GZIPOutputStream(file);
          TarOutputStream tar = new TarOutputStream(gzip, properties.getMaxUncompressedBytes())) {
        tar.putBytes(
            "manifest.json",
            json(
                Map.ofEntries(
                    Map.entry("schemaVersion", SCHEMA_VERSION),
                    Map.entry("generatedAt", job.createdAt().toString()),
                    Map.entry("projectId", job.projectId()),
                    Map.entry("format", "tar.gz"),
                    Map.entry("repositories", contractStats.repositories()),
                    Map.entry("contracts", contractStats.contracts()),
                    Map.entry("activityEvents", activityStats.events()),
                    Map.entry("repositoriesTruncated", contractStats.repositoriesTruncated()),
                    Map.entry("contractsTruncated", contractStats.contractsTruncated()),
                    Map.entry("activityTruncated", activityStats.truncated()))));

        tar.putBytes("project.json", projectJson(project));
        tar.putBytes("members.json", membersJson(job.projectId()));
        tar.putPath("contracts/index.json", contractStats.path());
        tar.putPath("activity/events-recent.ndjson", activityStats.path());
        tar.putBytes("redaction.json", redactionJson());
        tar.putBytes(
            "README.md",
            readme(project, contractStats, activityStats).getBytes(StandardCharsets.UTF_8));
        uncompressed = tar.uncompressedBytes();
      }
      return new Result(
          contractStats.repositories(),
          contractStats.contracts(),
          activityStats.events(),
          contractStats.repositoriesTruncated(),
          contractStats.contractsTruncated(),
          activityStats.truncated(),
          uncompressed);
    } finally {
      deleteRecursively(spool);
    }
  }

  private ContractStats writeContracts(String projectId, Path spool, Checkpoint checkpoint)
      throws IOException {
    Path path = spool.resolve("contracts.json");
    int pageSize = boundedPageSize();
    int repositories = 0;
    int contracts = 0;
    boolean contractsTruncated = false;

    try (OutputStream raw = Files.newOutputStream(path);
        OutputStream bounded = new BoundedOutputStream(raw, properties.getMaxUncompressedBytes());
        JsonGenerator json = mapper.getFactory().createGenerator(bounded)) {
      json.writeStartObject();
      json.writeArrayFieldStart("contracts");
      int linkPage = 0;
      boolean stop = false;
      while (!stop && repositories < properties.getMaxRepos()) {
        var links =
            linkRepo.findByProjectIdOrderById(projectId, PageRequest.of(linkPage++, pageSize));
        if (links.isEmpty()) break;
        for (ProjectRepoLink link : links) {
          if (repositories >= properties.getMaxRepos()) {
            stop = true;
            break;
          }
          repositories++;
          ProjectRepo repo = repoRepo.findById(link.getRepoId()).orElse(null);
          long repoContractCount = contractRepo.countByRepoId(link.getRepoId());
          int contractPage = 0;
          int visitedForRepo = 0;
          while (contracts < properties.getMaxContracts()) {
            var rows =
                contractRepo.findByRepoIdOrderById(
                    link.getRepoId(), PageRequest.of(contractPage++, pageSize));
            if (rows.isEmpty()) break;
            for (ContractRegistry contract : rows) {
              if (contracts >= properties.getMaxContracts()) break;
              writeContract(json, contract, repo);
              contracts++;
              visitedForRepo++;
            }
            checkpoint.check();
            if (rows.size() < pageSize || contracts >= properties.getMaxContracts()) break;
          }
          if (visitedForRepo < repoContractCount) contractsTruncated = true;
          if (contracts >= properties.getMaxContracts()) {
            stop = true;
            break;
          }
        }
        checkpoint.check();
        if (links.size() < pageSize) break;
      }
      boolean repositoriesTruncated = linkRepo.countByProjectId(projectId) > repositories;
      if (repositoriesTruncated) contractsTruncated = true;
      json.writeEndArray();
      json.writeNumberField("count", contracts);
      json.writeNumberField("repositoryCount", repositories);
      json.writeBooleanField("repositoriesTruncated", repositoriesTruncated);
      json.writeBooleanField("contractsTruncated", contractsTruncated);
      json.writeEndObject();
      return new ContractStats(
          path, repositories, contracts, repositoriesTruncated, contractsTruncated);
    }
  }

  private void writeContract(JsonGenerator json, ContractRegistry contract, ProjectRepo repo)
      throws IOException {
    json.writeStartObject();
    json.writeStringField("name", safe(contract.getName()));
    json.writeStringField("type", contract.typeEnum().name());
    json.writeNumberField(
        "version",
        contract.getCurrentRegistryVersion() == null ? 0 : contract.getCurrentRegistryVersion());
    json.writeStringField("contentHash", nv(contract.getCurrentContentHash()));
    json.writeStringField("detectionMethod", contract.detectionMethodEnum().name());
    json.writeStringField(
        "repository", repo == null ? "(unknown)" : safe(nv(repo.getGithubRepoFullName())));
    json.writeStringField("detectedPath", safe(nv(contract.getDetectedPath())));
    json.writeEndObject();
  }

  private ActivityStats writeActivity(
      ProjectExportJobStore.Job job, Path spool, Checkpoint checkpoint) throws IOException {
    Path path = spool.resolve("activity.ndjson");
    int events = 0;
    boolean truncated = false;
    int pageSize = boundedPageSize();
    UUID projectUuid;
    try {
      projectUuid = UUID.fromString(job.projectId());
    } catch (IllegalArgumentException ignored) {
      Files.write(path, new byte[0]);
      return new ActivityStats(path, 0, false);
    }

    Instant from = job.createdAt().minus(90, ChronoUnit.DAYS);
    Instant to = job.createdAt().plusNanos(1);
    try (OutputStream raw = Files.newOutputStream(path);
        OutputStream bounded = new BoundedOutputStream(raw, properties.getMaxUncompressedBytes());
        BufferedWriter writer =
            new BufferedWriter(new java.io.OutputStreamWriter(bounded, StandardCharsets.UTF_8))) {
      int page = 0;
      while (events < properties.getMaxActivityEvents()) {
        var rows =
            feedRepo.findProjectExportPage(projectUuid, from, to, PageRequest.of(page++, pageSize));
        if (rows.isEmpty()) break;
        for (ActivityFeedEntry event : rows) {
          if (events >= properties.getMaxActivityEvents()) {
            truncated = true;
            break;
          }
          writer.write(mapper.writeValueAsString(activityRow(event)));
          writer.newLine();
          events++;
        }
        checkpoint.check();
        if (rows.size() < pageSize) break;
      }
      if (events >= properties.getMaxActivityEvents()) {
        var extra = feedRepo.findProjectExportPage(projectUuid, from, to, PageRequest.of(page, 1));
        truncated = truncated || !extra.isEmpty();
      }
    }
    return new ActivityStats(path, events, truncated);
  }

  private Map<String, Object> activityRow(ActivityFeedEntry event) {
    String summary = safe(nv(event.getSummary()));
    if (summary.length() > MAX_SUMMARY_CHARS) {
      summary = summary.substring(0, MAX_SUMMARY_CHARS) + "...[truncated]";
    }
    return Map.of(
        "occurredAt", nv(event.getOccurredAt() == null ? null : event.getOccurredAt().toString()),
        "eventType", nv(event.getEventType()),
        "actorType", nv(event.getActorType()),
        "targetType", nv(event.getTargetType()),
        "summary", summary,
        "schemaVersion", event.getSchemaVersion() == null ? 1 : event.getSchemaVersion());
  }

  private byte[] projectJson(Project project) throws IOException {
    return json(
        Map.of(
            "id", nv(project.getId()),
            "name", safe(nv(project.getName())),
            "orgId", nv(project.getOrgId()),
            "status", nv(project.getStatus()),
            "createdAt",
                nv(project.getCreatedAt() == null ? null : project.getCreatedAt().toString())));
  }

  private byte[] membersJson(String projectId) throws IOException {
    return json(
        Map.of(
            "count", memberRepo.countByProjectId(projectId),
            "roles",
                Map.of(
                    "PROJECT_ADMIN",
                    memberRepo.countByProjectIdAndProjectRole(projectId, "PROJECT_ADMIN"),
                    "PROJECT_DEVELOPER",
                    memberRepo.countByProjectIdAndProjectRole(projectId, "PROJECT_DEVELOPER"),
                    "PROJECT_VIEWER",
                    memberRepo.countByProjectIdAndProjectRole(projectId, "PROJECT_VIEWER")),
            "identifiersOmitted", true));
  }

  private byte[] redactionJson() throws IOException {
    return json(
        Map.of(
            "policyVersion",
            1,
            "secretPatternRedaction",
            true,
            "omittedFields",
            new String[] {
              "credentials",
              "provider API keys",
              "repository clone credentials",
              "member/user identifiers",
              "activity payload",
              "activity actor/target identifiers"
            },
            "notes",
            "Summaries, names and paths are secret-pattern filtered; source code and uploaded"
                + " document bytes are not included."));
  }

  private String readme(Project project, ContractStats contracts, ActivityStats activity) {
    return """
           # ExecuteSpec project export

           **Project:** %s
           **Schema version:** %d

           This is a bounded metadata and governance snapshot. It is not a source-code backup.
           Customer repositories remain authoritative for source and `.executespec/` files.

           ## Included

           - `manifest.json` — counts and explicit truncation evidence
           - `project.json` — non-secret project metadata
           - `members.json` — aggregate role counts; user identifiers omitted
           - `contracts/index.json` — %d contract metadata row(s)
           - `activity/events-recent.ndjson` — %d recent, redacted activity row(s)
           - `redaction.json` — fields and secret classes deliberately omitted

           Consumers must reject unknown schema versions and inspect every `*Truncated` manifest
           field before treating this archive as complete for the covered metadata classes.
           """
        .formatted(
            safe(project.getName()), SCHEMA_VERSION, contracts.contracts(), activity.events());
  }

  private byte[] json(Object value) throws IOException {
    return mapper.writeValueAsBytes(value);
  }

  private String safe(String value) {
    return sanitizer.sanitizeOutput(value == null ? "" : value);
  }

  private int boundedPageSize() {
    return Math.max(1, Math.min(properties.getPageSize(), 1000));
  }

  private static String nv(String value) {
    return value == null ? "" : value;
  }

  private static void deleteRecursively(Path root) {
    if (root == null || !Files.exists(root)) return;
    try (var paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // Private temp cleanup is best effort; OS/temp-volume cleanup is the final
                  // fallback.
                }
              });
    } catch (IOException ignored) {
      // Best effort.
    }
  }

  private record ContractStats(
      Path path,
      int repositories,
      int contracts,
      boolean repositoriesTruncated,
      boolean contractsTruncated) {}

  private record ActivityStats(Path path, int events, boolean truncated) {}

  /** Thrown before a spool member can exceed the configured hard byte ceiling. */
  public static class ExportLimitExceededException extends IOException {
    ExportLimitExceededException() {
      super("project export exceeded configured byte ceiling");
    }
  }

  private static final class BoundedOutputStream extends FilterOutputStream {
    private final long limit;
    private long written;

    BoundedOutputStream(OutputStream out, long limit) {
      super(out);
      this.limit = limit;
    }

    @Override
    public void write(int value) throws IOException {
      reserve(1);
      out.write(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      reserve(length);
      out.write(bytes, offset, length);
    }

    private void reserve(long bytes) throws ExportLimitExceededException {
      if (bytes < 0 || written > limit - bytes) throw new ExportLimitExceededException();
      written += bytes;
    }
  }

  /** Minimal USTAR writer with a cumulative input ceiling and streaming file copy. */
  static final class TarOutputStream implements AutoCloseable {
    private final OutputStream out;
    private final long limit;
    private long uncompressedBytes;

    TarOutputStream(OutputStream out, long limit) {
      this.out = out;
      this.limit = limit;
    }

    long uncompressedBytes() {
      return uncompressedBytes;
    }

    void putBytes(String path, byte[] bytes) throws IOException {
      reserve(bytes.length);
      writeHeader(path, bytes.length);
      out.write(bytes);
      pad(bytes.length);
    }

    void putPath(String archivePath, Path source) throws IOException {
      long size = Files.size(source);
      reserve(size);
      writeHeader(archivePath, size);
      try (var in = Files.newInputStream(source)) {
        in.transferTo(out);
      }
      pad(size);
    }

    private void reserve(long bytes) throws ExportLimitExceededException {
      if (bytes < 0 || uncompressedBytes > limit - bytes) {
        throw new ExportLimitExceededException();
      }
      uncompressedBytes += bytes;
    }

    private void writeHeader(String path, long size) throws IOException {
      byte[] nameBytes = path.getBytes(StandardCharsets.UTF_8);
      if (nameBytes.length > 100) throw new IOException("archive entry name too long");
      byte[] header = new byte[512];
      System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
      writeOctal(header, 100, 8, 0644);
      writeOctal(header, 108, 8, 0);
      writeOctal(header, 116, 8, 0);
      writeOctal(header, 124, 12, size);
      writeOctal(header, 136, 12, Instant.now().getEpochSecond());
      for (int i = 148; i < 156; i++) header[i] = ' ';
      header[156] = '0';
      byte[] magic = "ustar\0".getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(magic, 0, header, 257, magic.length);
      header[263] = '0';
      header[264] = '0';
      int sum = 0;
      for (byte b : header) sum += b & 0xff;
      byte[] checksum = (String.format("%06o", sum) + "\0 ").getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(checksum, 0, header, 148, checksum.length);
      out.write(header);
    }

    private void pad(long size) throws IOException {
      int remainder = (int) (size % 512);
      if (remainder > 0) out.write(new byte[512 - remainder]);
    }

    private static void writeOctal(byte[] header, int offset, int length, long value) {
      String formatted = String.format("%0" + (length - 1) + "o", value);
      byte[] bytes = formatted.getBytes(StandardCharsets.US_ASCII);
      if (bytes.length > length - 1) throw new IllegalArgumentException("tar value too large");
      System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    @Override
    public void close() throws IOException {
      out.write(new byte[1024]);
      out.flush();
    }
  }
}
