package com.navneet.executespec.contracts.detection;

import com.navneet.executespec.contracts.ContractRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the 5 {@link FileBasedContractDetector} implementations
 * + {@link DetectorUtils}. Uses an in-memory {@link FileBasedContractDetector.ContentLoader}
 * so no GitHub round-trip happens.
 */
class DetectorsTest {

    static FileBasedContractDetector.ContentLoader loaderFor(Map<String, String> files) {
        return path -> files.get(path);
    }

    // ── DetectorUtils ───────────────────────────────────────────────────────

    @Test
    void sha256_isStable_andDifferentForDifferentInputs() {
        assertThat(DetectorUtils.sha256("hello")).isEqualTo(DetectorUtils.sha256("hello"));
        assertThat(DetectorUtils.sha256("hello")).isNotEqualTo(DetectorUtils.sha256("hellox"));
        assertThat(DetectorUtils.sha256(null)).isNull();
    }

    @Test
    void basenameWithoutExtension_handlesCommonShapes() {
        assertThat(DetectorUtils.basenameWithoutExtension("api/openapi.yaml")).isEqualTo("openapi");
        assertThat(DetectorUtils.basenameWithoutExtension("a/b/c.openapi.json")).isEqualTo("c");
        assertThat(DetectorUtils.basenameWithoutExtension("standalone.proto")).isEqualTo("standalone");
        assertThat(DetectorUtils.basenameWithoutExtension(null)).isEqualTo("unknown");
    }

    @Test
    void matchesGlob_supportsDoubleStarAndExtension() {
        assertThat(DetectorUtils.matchesGlob("a/b/c.proto", "**/*.proto")).isTrue();
        assertThat(DetectorUtils.matchesGlob("a.proto", "**/*.proto")).isTrue();
        assertThat(DetectorUtils.matchesGlob("a/b/openapi.yaml", "**/openapi.yaml")).isTrue();
        assertThat(DetectorUtils.matchesGlob("a/b/c.txt", "**/*.proto")).isFalse();
    }

    @Test
    void pullOpenApiVersion_extractsBareAndQuotedValues() {
        assertThat(DetectorUtils.pullOpenApiVersion("openapi: 3.0.0\ninfo:\n  version: 1.2.3\n"))
                .isEqualTo("1.2.3");
        assertThat(DetectorUtils.pullOpenApiVersion("info:\n  version: \"2.0.0-rc1\"\n"))
                .isEqualTo("2.0.0-rc1");
        assertThat(DetectorUtils.pullOpenApiVersion("nothing here")).isNull();
    }

    // ── OpenApiFileDetector ─────────────────────────────────────────────────

    @Test
    void openApi_findsBundledSpec() {
        OpenApiFileDetector det = new OpenApiFileDetector();
        Map<String, String> files = Map.of(
                "api/openapi.yaml", "openapi: 3.0.0\ninfo:\n  version: 1.0.0\n",
                "src/Main.java", "public class Main {}");
        List<DetectedContract> found = det.detect(List.copyOf(files.keySet()), loaderFor(files));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).type()).isEqualTo(ContractRegistry.ContractType.OPENAPI);
        assertThat(found.get(0).declaredSemver()).isEqualTo("1.0.0");
    }

    @Test
    void openApi_skipsFilesNamedOpenapiYamlButLackingContent() {
        OpenApiFileDetector det = new OpenApiFileDetector();
        Map<String, String> files = Map.of(
                "openapi.yaml", "this:\n  is: not openapi");
        List<DetectedContract> found = det.detect(List.copyOf(files.keySet()), loaderFor(files));
        assertThat(found).isEmpty();
    }

    // ── ProtoFileDetector ───────────────────────────────────────────────────

    @Test
    void proto_findsServiceDefinition_andSkipsBareProtos() {
        ProtoFileDetector det = new ProtoFileDetector();
        Map<String, String> files = Map.of(
                "proto/payments.proto",
                "syntax = \"proto3\";\nmessage Payment {}\nservice PaymentSvc {}\n",
                "proto/empty.proto", "// just a header comment\n");
        List<DetectedContract> found = det.detect(List.copyOf(files.keySet()), loaderFor(files));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).name()).isEqualTo("payments");
        assertThat(found.get(0).type()).isEqualTo(ContractRegistry.ContractType.PROTO);
    }

    // ── GraphQlSdlDetector ──────────────────────────────────────────────────

    @Test
    void graphql_findsSchemaWithQueryType() {
        GraphQlSdlDetector det = new GraphQlSdlDetector();
        Map<String, String> files = Map.of(
                "graphql/schema.graphql", "type Query { hello: String }");
        List<DetectedContract> found = det.detect(List.copyOf(files.keySet()), loaderFor(files));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).type()).isEqualTo(ContractRegistry.ContractType.GRAPHQL);
    }

    // ── JsonSchemaDetector ──────────────────────────────────────────────────

    @Test
    void jsonSchema_requiresDollarSchemaKey() {
        JsonSchemaDetector det = new JsonSchemaDetector();
        Map<String, String> files = Map.of(
                "schemas/user.schema.json",
                "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\"}",
                "schemas/no-key.schema.json", "{\"type\":\"object\"}");
        List<DetectedContract> found = det.detect(List.copyOf(files.keySet()), loaderFor(files));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).name()).isEqualTo("user");
    }

    // ── AsyncApiDetector ────────────────────────────────────────────────────

    @Test
    void asyncApi_findsRootKey() {
        AsyncApiDetector det = new AsyncApiDetector();
        Map<String, String> files = Map.of(
                "api/events.asyncapi.yaml",
                "asyncapi: 2.6.0\ninfo:\n  version: 1.0.0\n");
        List<DetectedContract> found = det.detect(List.copyOf(files.keySet()), loaderFor(files));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).type()).isEqualTo(ContractRegistry.ContractType.ASYNCAPI);
    }

    // ── Failure handling ────────────────────────────────────────────────────

    @Test
    void detector_skipsFileOnLoaderException_doesNotCrashBatch() {
        OpenApiFileDetector det = new OpenApiFileDetector();
        FileBasedContractDetector.ContentLoader brokenLoader = path -> {
            throw new RuntimeException("network blip");
        };
        List<DetectedContract> found = det.detect(List.of("api/openapi.yaml"), brokenLoader);
        assertThat(found).isEmpty(); // Logged + skipped, not propagated
    }

    @Test
    void detector_handlesEmptyFileList_returnsEmpty() {
        for (FileBasedContractDetector det : List.of(
                new OpenApiFileDetector(), new ProtoFileDetector(),
                new GraphQlSdlDetector(), new JsonSchemaDetector(),
                new AsyncApiDetector())) {
            assertThat(det.detect(List.of(), loaderFor(Map.of()))).isEmpty();
            assertThat(det.detect(null, loaderFor(Map.of()))).isEmpty();
        }
    }
}
