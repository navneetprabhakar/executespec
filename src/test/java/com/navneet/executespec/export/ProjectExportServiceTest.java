package com.navneet.executespec.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectExportServiceTest {

  @Mock ProjectRepository projectRepo;
  @Mock ProjectRepoLinkRepository linkRepo;
  @Mock ProjectRepoRepository projectRepoRepo;
  @Mock ContractRegistryRepository contractRepo;
  @Mock ActivityFeedEntryRepository feedRepo;

  ProjectExportService service;

  String projectId;

  @BeforeEach
  void setUp() {
    service =
        new ProjectExportService(
            projectRepo, linkRepo, projectRepoRepo, contractRepo, feedRepo, new ObjectMapper());
    projectId = UUID.randomUUID().toString();
    Project p = new Project();
    p.setId(projectId);
    p.setName("Acme Web");
    p.setOrgId("org-1");
    lenient().when(projectRepo.findById(projectId)).thenReturn(Optional.of(p));
    lenient().when(linkRepo.findByProjectId(projectId)).thenReturn(List.of());
    lenient().when(feedRepo.findRecentForProject(any(), any(), any())).thenReturn(List.of());
  }

  @Test
  void unknownProject_throws() {
    when(projectRepo.findById("nope")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.composeBundle("nope"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bundle_isValidGzipTar_andContainsExpectedFiles() throws Exception {
    byte[] bundle = service.composeBundle(projectId);
    Map<String, byte[]> contents = readTarGz(bundle);

    // Required files per plan §4.2 (subset for v1)
    assertThat(contents)
        .containsKeys(
            "manifest.json",
            "project.json",
            "members.json",
            "contracts/index.json",
            "activity/events-recent.ndjson",
            "README.md");
  }

  @Test
  void manifest_declaresSchemaVersion1() throws Exception {
    byte[] bundle = service.composeBundle(projectId);
    Map<String, byte[]> contents = readTarGz(bundle);
    String manifest = new String(contents.get("manifest.json"), StandardCharsets.UTF_8);
    assertThat(manifest).contains("\"schemaVersion\":1");
    assertThat(manifest).contains(projectId);
  }

  @Test
  void contractsIndex_includesEveryProducingRepoContract() throws Exception {
    UUID repoId = UUID.randomUUID();
    when(linkRepo.findByProjectId(projectId))
        .thenReturn(
            List.of(
                ProjectRepoLink.builder()
                    .id(UUID.randomUUID())
                    .projectId(projectId)
                    .repoId(repoId)
                    .build()));
    when(contractRepo.findByRepoId(repoId))
        .thenReturn(
            List.of(
                ContractRegistry.builder()
                    .id(UUID.randomUUID())
                    .repoId(repoId)
                    .name("widget-api")
                    .type(ContractRegistry.ContractType.OPENAPI.name())
                    .currentRegistryVersion(3)
                    .currentContentHash("h")
                    .detectionMethod(ContractRegistry.DetectionMethod.AUTO_FILE.name())
                    .detectedPath("api/openapi.yaml")
                    .build()));
    ProjectRepo repo = new ProjectRepo();
    repo.setId(repoId);
    repo.setGithubRepoFullName("acme/widget");
    when(projectRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));

    byte[] bundle = service.composeBundle(projectId);
    Map<String, byte[]> contents = readTarGz(bundle);
    String idx = new String(contents.get("contracts/index.json"), StandardCharsets.UTF_8);
    assertThat(idx).contains("\"count\":1");
    assertThat(idx).contains("widget-api");
    assertThat(idx).contains("acme/widget");
  }

  @Test
  void activity_includesRecentEventsAsNdjson() throws Exception {
    UUID projectUuid = UUID.fromString(projectId);
    when(feedRepo.findRecentForProject(any(), any(), any()))
        .thenReturn(
            List.of(
                ActivityFeedEntry.builder()
                    .id(UUID.randomUUID())
                    .projectId(projectUuid)
                    .occurredAt(Instant.now())
                    .eventType("RUN_SUBMITTED")
                    .actorType("USER")
                    .summary("submitted")
                    .schemaVersion(1)
                    .build(),
                ActivityFeedEntry.builder()
                    .id(UUID.randomUUID())
                    .projectId(projectUuid)
                    .occurredAt(Instant.now())
                    .eventType("PR_OPENED")
                    .actorType("SYSTEM")
                    .summary("PR opened")
                    .schemaVersion(1)
                    .build()));

    byte[] bundle = service.composeBundle(projectId);
    Map<String, byte[]> contents = readTarGz(bundle);
    String ndjson =
        new String(contents.get("activity/events-recent.ndjson"), StandardCharsets.UTF_8);
    // Two newline-separated JSON objects
    long lines = ndjson.lines().filter(l -> !l.isBlank()).count();
    assertThat(lines).isEqualTo(2);
    assertThat(ndjson).contains("RUN_SUBMITTED");
    assertThat(ndjson).contains("PR_OPENED");
  }

  @Test
  void readme_mentionsSchemaVersionAndContractCount() throws Exception {
    byte[] bundle = service.composeBundle(projectId);
    Map<String, byte[]> contents = readTarGz(bundle);
    String readme = new String(contents.get("README.md"), StandardCharsets.UTF_8);
    assertThat(readme).contains("Schema version:** 1");
    assertThat(readme).contains("0 contract(s)");
  }

  // tar.gz reader (USTAR; matches the writer in the service)

  static Map<String, byte[]> readTarGz(byte[] gz) throws IOException {
    Map<String, byte[]> out = new HashMap<>();
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
      byte[] header = new byte[512];
      while (true) {
        int n = in.readNBytes(header, 0, 512);
        if (n < 512) break;
        if (header[0] == 0) break; // end-of-archive blocks
        String name = readStr(header, 0, 100);
        long size = readOctal(header, 124, 12);
        byte[] data = in.readNBytes((int) size);
        int rem = (int) (size % 512);
        if (rem > 0) in.skip(512 - rem);
        if (!name.isEmpty()) out.put(name, data);
      }
    }
    return out;
  }

  private static String readStr(byte[] header, int offset, int len) {
    int end = offset;
    while (end < offset + len && header[end] != 0) end++;
    return new String(header, offset, end - offset, StandardCharsets.US_ASCII);
  }

  private static long readOctal(byte[] header, int offset, int len) {
    String s = readStr(header, offset, len).trim();
    return s.isEmpty() ? 0 : Long.parseLong(s, 8);
  }
}
