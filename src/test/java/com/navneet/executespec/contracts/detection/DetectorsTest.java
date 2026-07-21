package com.navneet.executespec.contracts.detection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.contracts.ContractRegistry;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit tests for the 5 {@link FileBasedContractDetector} implementations + {@link DetectorUtils}.
 * Uses an in-memory {@link FileBasedContractDetector.ContentLoader} so no GitHub round-trip
 * happens.
 */
class DetectorsTest {

  static FileBasedContractDetector.ContentLoader loaderFor(Map<String, String> files) {
    return path -> files.get(path);
  }

  @Test
  void sha256_isStable_andDifferentForDifferentInputs() {
    assertThat(DetectorUtils.sha256("hello")).isEqualTo(DetectorUtils.sha256("hello"));
    assertThat(DetectorUtils.sha256("hello")).isNotEqualTo(DetectorUtils.sha256("hellox"));
    assertThat(DetectorUtils.sha256(null)).isNull();
  }

  @Test
  void basenameWithoutExtension_handlesCommonShapes() {
    assertThat(DetectorUtils.basenameWithoutExtension("api/openapi.yaml")).isEqualTo("openapi");
    // RR-1653 — this asserted "c" (cut at the FIRST dot). That collapsed distinct contract files
    // onto one (repo_id, name) registry row; only the trailing known extension is stripped now.
    assertThat(DetectorUtils.basenameWithoutExtension("a/b/c.openapi.json")).isEqualTo("c.openapi");
    assertThat(DetectorUtils.basenameWithoutExtension("standalone.proto")).isEqualTo("standalone");
    assertThat(DetectorUtils.basenameWithoutExtension(null)).isEqualTo("unknown");
    // The compound .schema.json extension wins over the plain .json suffix, matching
    // DriftDetectionService.extensionFor(JSON_SCHEMA).
    assertThat(DetectorUtils.basenameWithoutExtension("schemas/user.schema.json"))
        .isEqualTo("user");
    // Unrecognised extension → full basename (documented fallback), not a first-dot truncation.
    assertThat(DetectorUtils.basenameWithoutExtension("a/b/notes.v2.txt"))
        .isEqualTo("notes.v2.txt");
  }

  @Test
  void basenameWithoutExtension_keepsVersionQualifiersDistinct_rr1653() {
    // The exact collision from RR-1653: two versioned protos in one repo used to resolve to the
    // single name "api", causing perpetual version flip-flop on one registry row.
    assertThat(DetectorUtils.basenameWithoutExtension("proto/api.v1.proto")).isEqualTo("api.v1");
    assertThat(DetectorUtils.basenameWithoutExtension("proto/api.v2.proto")).isEqualTo("api.v2");
    assertThat(DetectorUtils.basenameWithoutExtension("payments.openapi.yaml"))
        .isEqualTo("payments.openapi");
    assertThat(DetectorUtils.basenameWithoutExtension("payments.internal.yaml"))
        .isEqualTo("payments.internal");
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

  @Test
  void openApi_findsBundledSpec() {
    OpenApiFileDetector det = new OpenApiFileDetector();
    Map<String, String> files =
        Map.of(
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
    Map<String, String> files = Map.of("openapi.yaml", "this:\n  is: not openapi");
    List<DetectedContract> found = det.detect(List.copyOf(files.keySet()), loaderFor(files));
    assertThat(found).isEmpty();
  }

  @Test
  void proto_findsServiceDefinition_andSkipsBareProtos() {
    ProtoFileDetector det = new ProtoFileDetector();
    Map<String, String> files =
        Map.of(
            "proto/payments.proto",
            "syntax = \"proto3\";\nmessage Payment {}\nservice PaymentSvc {}\n",
            "proto/empty.proto",
            "// just a header comment\n");
    List<DetectedContract> found = det.detect(List.copyOf(files.keySet()), loaderFor(files));
    assertThat(found).hasSize(1);
    assertThat(found.get(0).name()).isEqualTo("payments");
    assertThat(found.get(0).type()).isEqualTo(ContractRegistry.ContractType.PROTO);
  }

  @Test
  void graphql_findsSchemaWithQueryType() {
    GraphQlSdlDetector det = new GraphQlSdlDetector();
    Map<String, String> files = Map.of("graphql/schema.graphql", "type Query { hello: String }");
    List<DetectedContract> found = det.detect(List.copyOf(files.keySet()), loaderFor(files));
    assertThat(found).hasSize(1);
    assertThat(found.get(0).type()).isEqualTo(ContractRegistry.ContractType.GRAPHQL);
  }

  @Test
  void jsonSchema_requiresDollarSchemaKey() {
    JsonSchemaDetector det = new JsonSchemaDetector();
    Map<String, String> files =
        Map.of(
            "schemas/user.schema.json",
            "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\"}",
            "schemas/no-key.schema.json",
            "{\"type\":\"object\"}");
    List<DetectedContract> found = det.detect(List.copyOf(files.keySet()), loaderFor(files));
    assertThat(found).hasSize(1);
    assertThat(found.get(0).name()).isEqualTo("user");
  }

  @Test
  void asyncApi_findsRootKey() {
    AsyncApiDetector det = new AsyncApiDetector();
    Map<String, String> files =
        Map.of("api/events.asyncapi.yaml", "asyncapi: 2.6.0\ninfo:\n  version: 1.0.0\n");
    List<DetectedContract> found = det.detect(List.copyOf(files.keySet()), loaderFor(files));
    assertThat(found).hasSize(1);
    assertThat(found.get(0).type()).isEqualTo(ContractRegistry.ContractType.ASYNCAPI);
  }

  @Test
  void detector_skipsFileOnLoaderException_doesNotCrashBatch() {
    OpenApiFileDetector det = new OpenApiFileDetector();
    FileBasedContractDetector.ContentLoader brokenLoader =
        path -> {
          throw new RuntimeException("network blip");
        };
    List<DetectedContract> found = det.detect(List.of("api/openapi.yaml"), brokenLoader);
    assertThat(found).isEmpty(); // Logged + skipped, not propagated
  }

  @Test
  void detectorReadFailures_doNotLogPathMessageOrThrowable() {
    Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    String secretPath = "schemas/customer-secret-token.schema.json";
    String secretMessage = "Authorization: Bearer should-never-reach-logs";
    FileBasedContractDetector.ContentLoader brokenLoader =
        path -> {
          throw new RuntimeException(secretMessage);
        };
    try {
      for (FileBasedContractDetector detector :
          List.of(
              new OpenApiFileDetector(),
              new ProtoFileDetector(),
              new GraphQlSdlDetector(),
              new JsonSchemaDetector(),
              new AsyncApiDetector())) {
        String candidate =
            detector instanceof ProtoFileDetector
                ? "customer-secret-token.proto"
                : detector instanceof GraphQlSdlDetector
                    ? "customer-secret-token.graphql"
                    : detector instanceof AsyncApiDetector
                        ? "customer-secret-token.asyncapi.yaml"
                        : detector instanceof OpenApiFileDetector
                            ? "customer-secret-token.openapi.yaml"
                            : secretPath;
        assertThat(detector.detect(List.of(candidate), brokenLoader)).isEmpty();
      }
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    assertThat(appender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .allSatisfy(
            message -> {
              assertThat(message).doesNotContain("customer-secret-token");
              assertThat(message).doesNotContain(secretMessage);
              assertThat(message).contains("errorClass=RuntimeException");
            });
    assertThat(appender.list).extracting(ILoggingEvent::getThrowableProxy).containsOnlyNulls();
  }

  @Test
  void detector_handlesEmptyFileList_returnsEmpty() {
    for (FileBasedContractDetector det :
        List.of(
            new OpenApiFileDetector(),
            new ProtoFileDetector(),
            new GraphQlSdlDetector(),
            new JsonSchemaDetector(),
            new AsyncApiDetector())) {
      assertThat(det.detect(List.of(), loaderFor(Map.of()))).isEmpty();
      assertThat(det.detect(null, loaderFor(Map.of()))).isEmpty();
    }
  }
}
