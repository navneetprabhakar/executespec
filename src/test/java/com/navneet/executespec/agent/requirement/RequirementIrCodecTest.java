package com.navneet.executespec.agent.requirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.requirement.RequirementIrCodec.RequirementIrCodecException;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.Completeness;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.Origin;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.RequirementKind;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.RequirementNode;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.RequirementState;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.SourceSpan;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.TaskBinding;

import org.junit.jupiter.api.Test;

class RequirementIrCodecTest {
  private final RequirementIrCodec codec = new RequirementIrCodec(new ObjectMapper());

  @Test
  void canonicalRoundTripPreservesUnicodeAndIsByteStable() {
    String source = "Afficher l’état ‘PRÊT’ ✅";
    RequirementNode requirement =
        new RequirementNode(
            "R1",
            source,
            RequirementKind.UI,
            Origin.USER_STATED,
            RequirementState.EXPLICIT,
            new SourceSpan(0, source.length(), 1, 1, 1, source.length() + 1),
            null,
            null,
            List.of(),
            List.of("T1"));
    RequirementIrDocument document =
        new RequirementIrDocument(
            1,
            "a".repeat(64),
            RequirementIrValidator.sha256(source),
            List.of(requirement),
            List.of(),
            List.of(new TaskBinding("T1", List.of("R1"))),
            List.of(),
            Completeness.READY);

    String first = codec.writeCanonical(document);
    RequirementIrDocument decoded = codec.read(first);

    assertThat(decoded).isEqualTo(document);
    assertThat(codec.writeCanonical(decoded)).isEqualTo(first);
    assertThat(codec.digest(decoded)).matches("[0-9a-f]{64}");
  }

  @Test
  void canonicalEmptyDocumentBytesAndDigestAreFrozen() {
    RequirementIrDocument document =
        new RequirementIrDocument(
            1,
            "a".repeat(64),
            RequirementIrValidator.sha256(""),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Completeness.READY);

    String canonical = codec.writeCanonical(document);

    assertThat(canonical)
        .isEqualTo(
            "{\"acceptanceCriteria\":[],\"compilerFingerprint\":\""
                + "a".repeat(64)
                + "\",\"completeness\":\"READY\",\"issues\":[],\"requirements\":[],"
                + "\"schemaVersion\":1,\"sourceSha256\":\""
                + RequirementIrValidator.sha256("")
                + "\",\"taskBindings\":[]}");
    assertThat(codec.digest(document))
        .isEqualTo("19d0411847c8f9bd5ab63b30f8245544166337632e39bceed76db02ba72c2456");
  }

  @Test
  void missingAndUnknownSchemaVersionsFailClosed() {
    assertThatThrownBy(() -> codec.read("{}"))
        .isInstanceOf(RequirementIrCodecException.class)
        .hasMessageContaining("schemaVersion");
    assertThatThrownBy(() -> codec.read("{\"schemaVersion\":99}"))
        .isInstanceOf(RequirementIrCodecException.class)
        .hasMessageContaining("unsupported");
  }

  @Test
  void strictReadDoesNotInheritPermissiveBaseMapperSettings() {
    ObjectMapper permissive =
        new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    RequirementIrCodec strictCodec = new RequirementIrCodec(permissive);
    String canonical = strictCodec.writeCanonical(emptyDocument(""));

    assertThatThrownBy(() -> strictCodec.read(canonical.replaceFirst("\\{", "{\"future\":true,")))
        .isInstanceOf(RequirementIrCodecException.class)
        .hasMessageContaining("deserialization failed");
    assertThatThrownBy(
            () ->
                strictCodec.read(
                    canonical.replace("\"compilerFingerprint\":\"" + "a".repeat(64) + "\",", "")))
        .isInstanceOf(RequirementIrCodecException.class)
        .hasMessageContaining("deserialization failed");
    assertThatThrownBy(
            () ->
                strictCodec.read(
                    canonical.replace(
                        "\"compilerFingerprint\":\"" + "a".repeat(64) + "\"",
                        "\"compilerFingerprint\":null")))
        .isInstanceOf(RequirementIrCodecException.class)
        .hasMessageContaining("deserialization failed");
    assertThatThrownBy(
            () -> strictCodec.read(canonical.replaceFirst("\\{", "{\"schemaVersion\":1,")))
        .isInstanceOf(RequirementIrCodecException.class)
        .hasMessageContaining("deserialization failed");
  }

  @Test
  void boundDigestChangesWithPlanSourceOrContract() {
    String source = "Create a screen.";
    RequirementIrDocument document =
        new RequirementIrCompiler()
            .compile(
                source,
                com.navneet.executespec.agent.Plan.builder()
                    .requirements(
                        List.of(
                            new com.navneet.executespec.agent.RequirementSpec(
                                "R1",
                                source,
                                com.navneet.executespec.agent.RequirementSpec.RequirementKind
                                    .FUNCTIONAL)))
                    .tasks(
                        List.of(
                            com.navneet.executespec.agent.PlannedTask.builder()
                                .id("T1")
                                .title("Create screen")
                                .meetsRequirements(List.of("R1"))
                                .build()))
                    .build());

    String digest = codec.boundDigest(document, "{\"plan\":1}", source);

    assertThat(codec.boundDigest(document, "{\"plan\":2}", source)).isNotEqualTo(digest);
    assertThatThrownBy(() -> codec.boundDigest(document, "{\"plan\":1}", source + " "))
        .isInstanceOf(RequirementIrCodecException.class)
        .hasMessageContaining("SOURCE_HASH_MISMATCH");
  }

  @Test
  void boundDigestUsesFrozenDomainSeparatedEnvelope() {
    RequirementIrDocument document = compilerOwnedEmptyDocument("");

    assertThat(codec.boundDigest(document, "{}", ""))
        .isEqualTo("1d15b0eef58f876e6b25cc369e5a536b44c22307e5fd36e444a823158aff5cd4");
  }

  private static RequirementIrDocument emptyDocument(String source) {
    return new RequirementIrDocument(
        1,
        "a".repeat(64),
        RequirementIrValidator.sha256(source),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Completeness.READY);
  }

  private static RequirementIrDocument compilerOwnedEmptyDocument(String source) {
    return new RequirementIrDocument(
        1,
        RequirementIrCompiler.COMPILER_FINGERPRINT,
        RequirementIrValidator.sha256(source),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Completeness.READY);
  }
}
