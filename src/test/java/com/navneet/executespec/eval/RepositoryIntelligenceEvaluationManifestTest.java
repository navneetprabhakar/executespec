package com.navneet.executespec.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class RepositoryIntelligenceEvaluationManifestTest {
  private static final Path FIXTURE_ROOT =
      Path.of("src/test/resources/eval/repository-intelligence/v1").toAbsolutePath().normalize();
  private static final Path MANIFEST = FIXTURE_ROOT.resolve("manifest.json");
  private static final String SHA_256_PATTERN = "[0-9a-f]{64}";

  @Test
  void frozenManifestIsCompleteContainedAndZeroSpend() throws Exception {
    JsonNode manifest = new ObjectMapper().readTree(MANIFEST.toFile());

    assertThat(manifest.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(manifest.path("datasetId").asText())
        .isEqualTo("repository-intelligence-planir-zero-spend-v1");
    assertThat(manifest.path("checkpoint").asText()).isEqualTo("RI-P0-C01");

    JsonNode policy = manifest.path("executionPolicy");
    assertThat(policy.path("liveProviderCallsAllowed").asBoolean()).isFalse();
    assertThat(policy.path("graphifyInstallationAllowed").asBoolean()).isFalse();
    assertThat(policy.path("deploymentAllowed").asBoolean()).isFalse();
    policy
        .path("providerBudgetUsd")
        .fields()
        .forEachRemaining(entry -> assertThat(entry.getValue().decimalValue()).isZero());

    Set<String> kinds = new HashSet<>();
    for (JsonNode fixtureCase : manifest.path("cases")) {
      kinds.add(fixtureCase.path("requirementKind").asText());
      assertContainedRegularFile(fixtureCase.path("requirementPath").asText());
      assertThat(fixtureCase.path("expectedSignals")).isNotEmpty();
      for (JsonNode repositoryRoot : fixtureCase.path("repositoryRoots")) {
        Path resolved = resolveContained(repositoryRoot.asText());
        assertThat(resolved).isDirectory();
      }
    }
    assertThat(kinds).containsExactlyInAnyOrder("EXPLICIT", "OMISSION", "CONFLICT", "AMBIGUITY");

    assertThat(manifest.path("assets")).hasSize(7);
    for (JsonNode asset : manifest.path("assets")) {
      Path path = assertContainedRegularFile(asset.path("path").asText());
      String expectedHash = asset.path("sha256").asText();
      assertThat(expectedHash).matches(SHA_256_PATTERN);
      assertThat(sha256(path)).isEqualTo(expectedHash);
    }

    assertThat(manifest.path("sourceFingerprints")).hasSizeGreaterThanOrEqualTo(7);
    for (JsonNode fingerprint : manifest.path("sourceFingerprints")) {
      assertThat(fingerprint.path("path").asText()).isNotBlank();
      assertThat(fingerprint.path("sha256").asText()).matches(SHA_256_PATTERN);
    }
  }

  private static Path assertContainedRegularFile(String relativePath) {
    Path path = resolveContained(relativePath);
    assertThat(path).isRegularFile();
    return path;
  }

  private static Path resolveContained(String relativePath) {
    assertThat(relativePath).isNotBlank();
    Path path = FIXTURE_ROOT.resolve(relativePath).normalize();
    assertThat(path).startsWith(FIXTURE_ROOT);
    return path;
  }

  private static String sha256(Path path) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }
}
