package com.navneet.executespec.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.activity.ActivityFeedEntry;
import com.navneet.executespec.activity.ActivityFeedEntryRepository;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.security.ArtifactPublicationGuard;
import com.navneet.executespec.security.ContextSanitizer;
import com.navneet.executespec.security.GeneratedSecretScanner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectExportBundleWriterTest {

  @Mock ProjectRepository projectRepo;
  @Mock ProjectMemberRepository memberRepo;
  @Mock ProjectRepoLinkRepository linkRepo;
  @Mock ProjectRepoRepository repoRepo;
  @Mock ContractRegistryRepository contractRepo;
  @Mock ActivityFeedEntryRepository feedRepo;

  @TempDir Path temp;

  private ProjectExportProperties properties;
  private ProjectExportBundleWriter writer;
  private String projectId;

  @BeforeEach
  void setUp() {
    properties = new ProjectExportProperties();
    AgentProperties agentProperties = new AgentProperties();
    agentProperties.setWorkspace(temp.toString());
    writer =
        new ProjectExportBundleWriter(
            projectRepo,
            memberRepo,
            linkRepo,
            repoRepo,
            contractRepo,
            feedRepo,
            new ObjectMapper(),
            new ContextSanitizer(),
            new ArtifactPublicationGuard(agentProperties, new GeneratedSecretScanner()),
            properties);
    projectId = UUID.randomUUID().toString();
    Project project =
        Project.builder()
            .id(projectId)
            .orgId("org-1")
            .name("Acme")
            .createdBy("user-secret")
            .createdAt(Instant.parse("2026-07-17T00:00:00Z"))
            .build();
    lenient().when(projectRepo.findById(projectId)).thenReturn(Optional.of(project));
    lenient().when(linkRepo.findByProjectIdOrderById(any(), any())).thenReturn(List.of());
    lenient().when(linkRepo.countByProjectId(projectId)).thenReturn(0L);
    lenient().when(memberRepo.countByProjectId(projectId)).thenReturn(3L);
    lenient().when(memberRepo.countByProjectIdAndProjectRole(any(), any())).thenReturn(1);
    lenient()
        .when(feedRepo.findProjectExportPage(any(), any(), any(), any()))
        .thenReturn(List.of());
  }

  @Test
  void writesSchemaV2TarWithoutUserIdentifiersAndWithRedactionManifest() throws Exception {
    UUID actor = UUID.randomUUID();
    when(feedRepo.findProjectExportPage(any(), any(), any(), any()))
        .thenReturn(
            List.of(
                ActivityFeedEntry.builder()
                    .id(UUID.randomUUID())
                    .projectId(UUID.fromString(projectId))
                    .occurredAt(Instant.parse("2026-07-16T00:00:00Z"))
                    .eventType("RUN_COMPLETED")
                    .actorType("USER")
                    .actorUserId(actor)
                    .targetType("RUN")
                    .targetId("internal-run-id")
                    .summary("api_key=abcdefghijklmnopsecret")
                    .schemaVersion(1)
                    .build()));

    Path archive = temp.resolve("export.tar.gz");
    var result = writer.write(job(), archive, () -> {});
    Map<String, byte[]> entries = readTarGz(java.nio.file.Files.readAllBytes(archive));

    assertThat(entries)
        .containsKeys(
            "manifest.json",
            "project.json",
            "members.json",
            "contracts/index.json",
            "activity/events-recent.ndjson",
            "redaction.json",
            "README.md");
    assertThat(text(entries, "manifest.json")).contains("\"schemaVersion\":2");
    assertThat(text(entries, "members.json")).contains("\"identifiersOmitted\":true");
    assertThat(text(entries, "redaction.json")).contains("provider API keys");
    assertThat(text(entries, "project.json")).doesNotContain("user-secret");
    assertThat(text(entries, "activity/events-recent.ndjson"))
        .contains("[REDACTED]")
        .doesNotContain(actor.toString(), "internal-run-id", "abcdefghijklmnopsecret");
    assertThat(result.activityEvents()).isEqualTo(1);
  }

  @Test
  void reportsActivityTruncationInsteadOfClaimingCompleteness() throws Exception {
    properties.setMaxActivityEvents(1);
    ActivityFeedEntry first =
        ActivityFeedEntry.builder()
            .projectId(UUID.fromString(projectId))
            .occurredAt(Instant.parse("2026-07-16T00:00:00Z"))
            .eventType("RUN_COMPLETED")
            .actorType("SYSTEM")
            .summary("one")
            .build();
    ActivityFeedEntry second =
        ActivityFeedEntry.builder()
            .projectId(UUID.fromString(projectId))
            .occurredAt(Instant.parse("2026-07-15T00:00:00Z"))
            .eventType("RUN_FAILED")
            .actorType("SYSTEM")
            .summary("two")
            .build();
    when(feedRepo.findProjectExportPage(any(), any(), any(), any()))
        .thenReturn(List.of(first, second));

    Path archive = temp.resolve("bounded.tar.gz");
    var result = writer.write(job(), archive, () -> {});

    assertThat(result.activityEvents()).isEqualTo(1);
    assertThat(result.activityTruncated()).isTrue();
    assertThat(text(readTarGz(java.nio.file.Files.readAllBytes(archive)), "manifest.json"))
        .contains("\"activityTruncated\":true");
  }

  @Test
  void hardByteCeilingFailsBeforeUnboundedArchiveGrowth() {
    properties.setMaxUncompressedBytes(16);
    Path archive = temp.resolve("too-large.tar.gz");

    assertThatThrownBy(() -> writer.write(job(), archive, () -> {}))
        .isInstanceOf(ProjectExportBundleWriter.ExportLimitExceededException.class);
  }

  private ProjectExportJobStore.Job job() {
    Instant created = Instant.parse("2026-07-17T00:00:00Z");
    return new ProjectExportJobStore.Job(
        UUID.randomUUID(),
        projectId,
        "org-1",
        "user-1",
        "request-key",
        ProjectExportJobStore.Status.RUNNING,
        "project-export.tar.gz",
        "application/gzip",
        null,
        null,
        null,
        1,
        created,
        "worker",
        1,
        created.plusSeconds(600),
        null,
        created,
        created,
        null,
        null,
        null,
        null,
        0);
  }

  private static String text(Map<String, byte[]> entries, String name) {
    return new String(entries.get(name), StandardCharsets.UTF_8);
  }

  static Map<String, byte[]> readTarGz(byte[] gzip) throws IOException {
    Map<String, byte[]> entries = new HashMap<>();
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
      byte[] header = new byte[512];
      while (in.readNBytes(header, 0, 512) == 512 && header[0] != 0) {
        String name = readString(header, 0, 100);
        long size = Long.parseLong(readString(header, 124, 12).trim(), 8);
        entries.put(name, in.readNBytes((int) size));
        int remainder = (int) (size % 512);
        if (remainder > 0) in.skipNBytes(512 - remainder);
      }
    }
    return entries;
  }

  private static String readString(byte[] bytes, int offset, int length) {
    int end = offset;
    while (end < offset + length && bytes[end] != 0) end++;
    return new String(bytes, offset, end - offset, StandardCharsets.US_ASCII);
  }
}
