package com.navneet.executespec.agent.requirement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.navneet.executespec.agent.Classification;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.RequirementSpec;
import com.navneet.executespec.agent.RequirementSpec.RequirementKind;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.IssueCode;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.Origin;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.RequirementState;
import com.navneet.executespec.agent.requirement.RequirementIrValidator.ViolationCode;

import org.junit.jupiter.api.Test;

class RequirementIrCompilerFrozenFixtureTest {

  private static final Path FIXTURE_ROOT =
      Path.of("src/test/resources/eval/repository-intelligence/v1/requirements")
          .toAbsolutePath()
          .normalize();

  private final RequirementIrCompiler compiler = new RequirementIrCompiler();

  @Test
  void emptyWhitespaceAndHeadingOnlySourcesNeverBecomeApprovable() {
    for (String source : List.of("", "   \n", "# Requirements")) {
      RequirementIrDocument document = compiler.compile(source, plan(List.of(), List.of()));

      assertThat(document.completeness()).isEqualTo(RequirementIrDocument.Completeness.BLOCKED);
      assertThat(RequirementIrValidator.validateForApproval(document, source).approvable())
          .isFalse();
    }
  }

  @Test
  void headingWithoutBlankLineDoesNotHideFollowingRequirement() {
    String source = "# Requirements\nImplement the order lookup.";

    RequirementIrDocument document = compiler.compile(source, plan(List.of(), List.of()));

    assertThat(document.requirements())
        .extracting(RequirementIrDocument.RequirementNode::text)
        .containsExactly("Implement the order lookup.");
    assertThat(issueCodes(document)).contains(IssueCode.UNCOVERED_SOURCE_CLAUSE);
    assertThat(RequirementIrValidator.validateForApproval(document, source).approvable()).isFalse();
  }

  @Test
  void duplicateTaskIdsBlockButCompilerStillEmitsStructurallyValidArtifact() {
    String source = "Implement the order lookup.";
    RequirementSpec requirement = requirement("R1", source);
    Plan plan =
        plan(
            List.of(requirement),
            List.of(
                task("T1", "First owner", List.of("R1")),
                task("T1", "Duplicate owner", List.of("R1"))));

    RequirementIrDocument document = compiler.compile(source, plan);

    assertThat(document.completeness()).isEqualTo(RequirementIrDocument.Completeness.BLOCKED);
    assertThat(issueCodes(document)).contains(IssueCode.DUPLICATE_TASK_ID);
    assertThat(document.taskBindings()).hasSize(1);
    assertThat(RequirementIrValidator.validateStructure(document, source).valid()).isTrue();
    assertThat(RequirementIrValidator.validateForApproval(document, source).approvable()).isFalse();
  }

  @Test
  void repeatedIdenticalSourceClausesBindToDistinctOccurrences() {
    String clause = "Persist the audit record.";
    String source = clause + "\n\n" + clause;
    Plan plan =
        plan(
            List.of(requirement("R1", clause), requirement("R2", clause)),
            List.of(
                task("T1", "First audit owner", List.of("R1")),
                task("T2", "Second audit owner", List.of("R2"))));

    RequirementIrDocument document = compiler.compile(source, plan);

    assertThat(document.requirements())
        .extracting(node -> node.sourceSpan().startOffset())
        .containsExactly(0, clause.length() + 2);
    assertThat(RequirementIrValidator.validateStructure(document, source).valid()).isTrue();
    assertThat(RequirementIrValidator.validateForApproval(document, source).approvable()).isTrue();
  }

  @Test
  void acceptancePredicatesCarryAtomicUtf16Spans() {
    String source = "✅ Expose POST /orders and return 201 with response fields.";
    Plan plan =
        plan(
            List.of(requirement("R1", source)),
            List.of(task("T1", "Implement contract", List.of("R1"))));

    RequirementIrDocument document = compiler.compile(source, plan);

    assertThat(document.acceptanceCriteria())
        .extracting(RequirementIrDocument.AcceptanceCriterion::expected)
        .contains("POST /orders", "201", "return", "response fields");
    assertThat(document.acceptanceCriteria())
        .allSatisfy(
            criterion -> {
              var span = criterion.sourceSpan();
              assertThat(span).isNotNull();
              assertThat(source.substring(span.startOffset(), span.endOffset()))
                  .isEqualTo(criterion.expected());
            });
  }

  @Test
  void explicitCrossRepositoryContractPreservesExactSourceAndIsStable() throws Exception {
    String source = fixture("explicit-cross-repo.md");
    String exactRequirement = contentClauses(source).get(0);
    Plan plan =
        plan(
            List.of(new RequirementSpec("R1", exactRequirement, RequirementKind.FUNCTIONAL)),
            List.of(
                task("T1", "Implement Java order endpoint", List.of("R1")),
                task("T2", "Update TypeScript order client", List.of("R1")),
                task("T3", "Preserve Python worker", List.of("R1"))));

    RequirementIrDocument first = compiler.compile(source, plan);
    RequirementIrDocument second = compiler.compile(source, plan);

    assertThat(first).isEqualTo(second);
    assertThat(first.schemaVersion()).isEqualTo(RequirementIrDocument.CURRENT_SCHEMA_VERSION);
    assertThat(first.compilerFingerprint()).isEqualTo(second.compilerFingerprint()).isNotBlank();
    assertThat(first.compilerFingerprint())
        .isEqualTo("8730dfa7ae62e27368afcd1a91192270950c82707b8845816b67d6c687a5371d");
    assertThat(first.sourceSha256()).isEqualTo(second.sourceSha256()).matches("[0-9a-f]{64}");
    assertThat(first.requirements())
        .extracting(RequirementIrDocument.RequirementNode::origin)
        .containsOnly(Origin.USER_STATED);
    assertEverySourceSpanIsExact(first, source);
    assertThat(RequirementIrValidator.validateStructure(first, source).valid()).isTrue();
    assertThat(RequirementIrValidator.validateForApproval(first, source).approvable()).isTrue();
  }

  @Test
  void omissionFixtureRemainsBlockedInsteadOfInventingApiErrorUiOrTestContracts() throws Exception {
    String source = fixture("omission.md");
    Plan plan =
        plan(
            List.of(requirement("R1", "Add an order lookup screen.")),
            List.of(task("T1", "Add order lookup screen", List.of("R1"))));

    RequirementIrDocument document = compiler.compile(source, plan);

    assertThat(document).isEqualTo(compiler.compile(source, plan));
    assertThat(document.requirements())
        .extracting(RequirementIrDocument.RequirementNode::state)
        .contains(RequirementState.NEEDS_CLARIFICATION);
    assertThat(issueCodes(document)).contains(IssueCode.MISSING_ACCEPTANCE);
    assertThat(RequirementIrValidator.validateForApproval(document, source).approvable()).isFalse();
    assertThat(RequirementIrValidator.validateForApproval(document, source).blockingCodes())
        .contains(IssueCode.MISSING_ACCEPTANCE);
    assertThat(document.requirements())
        .noneMatch(
            node ->
                node.text().contains("/api/")
                    || node.text().matches("(?s).*\\b(?:200|404|500)\\b.*")
                    || node.text().toLowerCase().contains("retry"));
    assertEverySourceSpanIsExact(document, source);
  }

  @Test
  void conflictFixtureBlocksApprovalWithoutSilentlyChoosingGetOrPost() throws Exception {
    String source = fixture("conflict.md");
    String exactRequirement = contentClauses(source).get(0);
    Plan plan =
        plan(
            List.of(requirement("R1", exactRequirement)),
            List.of(
                task("T1", "Expose Java GET route", List.of("R1")),
                task("T2", "Call TypeScript POST route", List.of("R1"))));

    RequirementIrDocument document = compiler.compile(source, plan);

    assertThat(document).isEqualTo(compiler.compile(source, plan));
    assertThat(issueCodes(document)).contains(IssueCode.CONFLICTING_CONTRACT);
    assertThat(document.issues())
        .filteredOn(issue -> issue.code() == IssueCode.CONFLICTING_CONTRACT)
        .allMatch(issue -> issue.blocking() && issue.requirementIds().contains("R1"));
    assertThat(RequirementIrValidator.validateForApproval(document, source).approvable()).isFalse();
    assertThat(RequirementIrValidator.validateForApproval(document, source).blockingCodes())
        .contains(IssueCode.CONFLICTING_CONTRACT);
    assertThat(document.requirements())
        .extracting(RequirementIrDocument.RequirementNode::text)
        .anyMatch(text -> text.contains("GET /api/orders/{orderId}"))
        .anyMatch(text -> text.contains("POST /api/order/search"));
    assertEverySourceSpanIsExact(document, source);
  }

  @Test
  void ambiguityFixtureKeepsUndefinedFallbackAndRetryPolicyAsClarification() throws Exception {
    String source = fixture("ambiguity.md");
    String exactRequirement = contentClauses(source).get(0);
    Plan plan =
        plan(
            List.of(requirement("R1", exactRequirement)),
            List.of(task("T1", "Handle unavailable order", List.of("R1"))));

    RequirementIrDocument document = compiler.compile(source, plan);

    assertThat(document).isEqualTo(compiler.compile(source, plan));
    assertThat(document.requirements())
        .extracting(RequirementIrDocument.RequirementNode::state)
        .contains(RequirementState.NEEDS_CLARIFICATION);
    assertThat(issueCodes(document)).contains(IssueCode.AMBIGUOUS_SOURCE);
    assertThat(RequirementIrValidator.validateForApproval(document, source).approvable()).isFalse();
    assertThat(RequirementIrValidator.validateForApproval(document, source).blockingCodes())
        .contains(IssueCode.AMBIGUOUS_SOURCE);
    assertThat(document.requirements())
        .extracting(RequirementIrDocument.RequirementNode::text)
        .noneMatch(
            text ->
                text.matches("(?s).*\\b[1-9][0-9]* retries\\b.*")
                    || text.toLowerCase().contains("exponential backoff"));
    assertEverySourceSpanIsExact(document, source);
  }

  @Test
  void structureValidationRejectsCopiedDocumentAgainstDifferentSource() throws Exception {
    String source = fixture("explicit-cross-repo.md");
    Plan plan =
        plan(
            List.of(requirement("R1", "Add GET /api/orders/{orderId} in the Java service.")),
            List.of(task("T1", "Implement Java order endpoint", List.of("R1"))));
    RequirementIrDocument document = compiler.compile(source, plan);

    var result = RequirementIrValidator.validateStructure(document, source + "\nmutated");

    assertThat(result.valid()).isFalse();
    assertThat(result.violations())
        .extracting(violation -> violation.code())
        .contains(ViolationCode.SOURCE_HASH_MISMATCH);
  }

  private static Set<IssueCode> issueCodes(RequirementIrDocument document) {
    return document.issues().stream()
        .map(RequirementIrDocument.RequirementIssue::code)
        .collect(Collectors.toSet());
  }

  private static void assertEverySourceSpanIsExact(RequirementIrDocument document, String source) {
    assertThat(document.requirements())
        .allSatisfy(
            requirement -> {
              assertThat(requirement.sourceSpan()).isNotNull();
              var span = requirement.sourceSpan();
              assertThat(span.startOffset()).isBetween(0, source.length());
              assertThat(span.endOffset()).isBetween(span.startOffset(), source.length());
              assertThat(source.substring(span.startOffset(), span.endOffset()))
                  .isEqualTo(requirement.text());
              assertThat(span.startLine()).isPositive();
              assertThat(span.startColumn()).isPositive();
              assertThat(span.endLine()).isPositive();
              assertThat(span.endColumn()).isPositive();
            });
  }

  private static String fixture(String name) throws IOException {
    Path path = FIXTURE_ROOT.resolve(name).normalize();
    assertThat(path).startsWith(FIXTURE_ROOT).isRegularFile();
    return Files.readString(path);
  }

  private static List<String> contentClauses(String source) {
    return java.util.Arrays.stream(source.split("\\n\\n"))
        .map(String::strip)
        .filter(value -> !value.isBlank() && !value.startsWith("#"))
        .toList();
  }

  private static Plan plan(List<RequirementSpec> requirements, List<PlannedTask> tasks) {
    return Plan.builder()
        .planTitle("Frozen Requirement IR fixture")
        .planSummary("Deterministic zero-spend fixture")
        .projectType("multi_repo")
        .runtimeSurface(Plan.RuntimeSurface.HTTP)
        .smokeTestStrategy(Plan.SmokeTestStrategy.TEST_SUITE)
        .classification(Classification.MEDIUM)
        .requirements(requirements)
        .tasks(tasks)
        .build();
  }

  private static RequirementSpec requirement(String id, String text) {
    return new RequirementSpec(id, text, RequirementKind.FUNCTIONAL);
  }

  private static PlannedTask task(String id, String title, List<String> requirementIds) {
    return PlannedTask.builder()
        .id(id)
        .title(title)
        .description(title + " and verify the exact source contract.")
        .complexity(PlannedTask.Complexity.MEDIUM)
        .touchesFiles(
            List.of("repos/fixture/src/" + id + ".java", "repos/fixture/test/" + id + "Test.java"))
        .dependsOn(List.of())
        .hardConstraints(List.of())
        .meetsRequirements(requirementIds)
        .build();
  }
}
