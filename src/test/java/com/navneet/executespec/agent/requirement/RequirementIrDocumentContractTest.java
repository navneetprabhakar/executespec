package com.navneet.executespec.agent.requirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.agent.requirement.RequirementIrDocument.AcceptanceCriterion;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.AcceptanceKind;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.Completeness;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.EvidenceType;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.Origin;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.RequirementIssue;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.RequirementKind;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.RequirementNode;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.RequirementState;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.SourceSpan;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.TaskBinding;
import com.navneet.executespec.agent.requirement.RequirementIrValidator.ViolationCode;

import org.junit.jupiter.api.Test;

class RequirementIrDocumentContractTest {

  @Test
  void documentAndNestedCollectionsAreDefensiveImmutableCopies() {
    List<String> acceptanceIds = new ArrayList<>(List.of("A1"));
    List<String> taskIds = new ArrayList<>(List.of("T1"));
    RequirementNode requirement =
        new RequirementNode(
            "R1",
            "Return 404 when the order is absent.",
            RequirementKind.ERROR,
            Origin.USER_STATED,
            RequirementState.EXPLICIT,
            span(0, 36),
            null,
            null,
            acceptanceIds,
            taskIds);
    List<RequirementNode> requirements = new ArrayList<>(List.of(requirement));
    List<String> criterionRequirementIds = new ArrayList<>(List.of("R1"));
    AcceptanceCriterion criterion =
        new AcceptanceCriterion(
            "A1",
            criterionRequirementIds,
            AcceptanceKind.STATUS_CODE,
            EvidenceType.FUNCTIONAL_TEST,
            "404",
            span(7, 10));
    List<AcceptanceCriterion> criteria = new ArrayList<>(List.of(criterion));
    List<String> bindingRequirementIds = new ArrayList<>(List.of("R1"));
    List<TaskBinding> bindings =
        new ArrayList<>(List.of(new TaskBinding("T1", bindingRequirementIds)));
    List<String> issueRequirementIds = new ArrayList<>(List.of("R1"));
    List<SourceSpan> issueSpans = new ArrayList<>(List.of(span(0, 36)));
    List<RequirementIssue> issues =
        new ArrayList<>(
            List.of(
                new RequirementIssue(
                    RequirementIrDocument.IssueCode.AMBIGUOUS_SOURCE,
                    true,
                    "ambiguous",
                    issueRequirementIds,
                    issueSpans)));

    RequirementIrDocument document =
        new RequirementIrDocument(
            RequirementIrDocument.CURRENT_SCHEMA_VERSION,
            "compiler-v1",
            "a".repeat(64),
            requirements,
            criteria,
            bindings,
            issues,
            Completeness.BLOCKED);

    requirements.clear();
    criteria.clear();
    bindings.clear();
    issues.clear();
    acceptanceIds.clear();
    taskIds.clear();
    criterionRequirementIds.clear();
    bindingRequirementIds.clear();
    issueRequirementIds.clear();
    issueSpans.clear();

    assertThat(document.requirements()).containsExactly(requirement);
    assertThat(document.acceptanceCriteria()).containsExactly(criterion);
    assertThat(document.taskBindings()).hasSize(1);
    assertThat(document.issues()).hasSize(1);
    assertThat(requirement.acceptanceIds()).containsExactly("A1");
    assertThat(requirement.taskIds()).containsExactly("T1");
    assertThat(criterion.requirementIds()).containsExactly("R1");
    assertThat(document.taskBindings().get(0).requirementIds()).containsExactly("R1");
    assertThat(document.issues().get(0).requirementIds()).containsExactly("R1");
    assertThat(document.issues().get(0).sourceSpans()).containsExactly(span(0, 36));

    assertThatThrownBy(() -> document.requirements().add(requirement))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> requirement.acceptanceIds().add("A2"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> criterion.requirementIds().add("R2"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void nullCollectionsNormalizeToEmptyWithoutInventingEvidence() {
    RequirementNode requirement =
        new RequirementNode(
            "R1",
            "Create an order lookup screen.",
            RequirementKind.UI,
            Origin.USER_STATED,
            RequirementState.NEEDS_CLARIFICATION,
            span(0, 30),
            null,
            null,
            null,
            null);
    AcceptanceCriterion criterion =
        new AcceptanceCriterion(
            "A1",
            null,
            AcceptanceKind.UI_INTERACTION,
            EvidenceType.UI_TEST,
            "lookup is observable",
            span(0, 30));
    RequirementIssue issue =
        new RequirementIssue(
            RequirementIrDocument.IssueCode.MISSING_ACCEPTANCE,
            true,
            "missing acceptance",
            null,
            null);

    RequirementIrDocument document =
        new RequirementIrDocument(1, "compiler-v1", "b".repeat(64), null, null, null, null, null);

    assertThat(document.requirements()).isEmpty();
    assertThat(document.acceptanceCriteria()).isEmpty();
    assertThat(document.taskBindings()).isEmpty();
    assertThat(document.issues()).isEmpty();
    assertThat(requirement.acceptanceIds()).isEmpty();
    assertThat(requirement.taskIds()).isEmpty();
    assertThat(criterion.requirementIds()).isEmpty();
    assertThat(issue.requirementIds()).isEmpty();
    assertThat(issue.sourceSpans()).isEmpty();
    assertThat(document.completeness()).isNull();
  }

  @Test
  void sourceSpanUsesZeroBasedEndExclusiveOffsetsAndOneBasedCoordinates() {
    String source = "Header\nReturn 404 when absent.";
    int start = source.indexOf("Return");
    int end = source.length();
    SourceSpan span = new SourceSpan(start, end, 2, 1, 2, end - start + 1);

    assertThat(source.substring(span.startOffset(), span.endOffset()))
        .isEqualTo("Return 404 when absent.");
    assertThat(span.startLine()).isEqualTo(2);
    assertThat(span.startColumn()).isEqualTo(1);
    assertThat(span.endLine()).isEqualTo(2);
    assertThat(span.endColumn()).isEqualTo("Return 404 when absent.".length() + 1);
  }

  @Test
  void sourceSpanOffsetsAndColumnsAreUtf16CodeUnits() {
    String source = "😀 Header\nReturn 404.";
    int start = source.indexOf("Return");
    SourceSpan span = new SourceSpan(start, source.length(), 2, 1, 2, 12);

    assertThat(start).isEqualTo(10);
    assertThat(RequirementIrValidator.spanMatches(source, span, "Return 404.")).isTrue();
  }

  @Test
  void userStatedRequirementMustMatchItsExactSourceSpan() {
    String source = "Return 404 when the order is absent.";
    RequirementNode requirement =
        new RequirementNode(
            "R1",
            source,
            RequirementKind.FUNCTIONAL,
            Origin.USER_STATED,
            RequirementState.EXPLICIT,
            new SourceSpan(0, source.length() - 1, 1, 1, 1, source.length()),
            null,
            null,
            List.of(),
            List.of("T1"));
    RequirementIrDocument document =
        document(source, requirement, new TaskBinding("T1", List.of("R1")));

    var result = RequirementIrValidator.validateStructure(document, source);

    assertThat(result.valid()).isFalse();
    assertThat(result.violations())
        .extracting(violation -> violation.code())
        .contains(ViolationCode.SOURCE_SPAN_MISMATCH);
  }

  @Test
  void inferredRequirementCannotClaimExactSourceProvenanceAndNeedsRationale() {
    String source = "Add an order lookup screen.";
    RequirementNode requirement =
        new RequirementNode(
            "R1",
            source,
            RequirementKind.UI,
            Origin.INFERRED,
            RequirementState.NEEDS_CLARIFICATION,
            new SourceSpan(0, source.length(), 1, 1, 1, source.length() + 1),
            null,
            null,
            List.of(),
            List.of("T1"));
    RequirementIrDocument document =
        document(source, requirement, new TaskBinding("T1", List.of("R1")));

    var result = RequirementIrValidator.validateStructure(document, source);

    assertThat(result.valid()).isFalse();
    assertThat(result.violations())
        .extracting(violation -> violation.code())
        .contains(ViolationCode.INVALID_PROVENANCE);
  }

  @Test
  void validUserStatedProvenanceAndReciprocalTaskBindingPassStructureValidation() {
    String source = "Add an order lookup screen.";
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
        document(source, requirement, new TaskBinding("T1", List.of("R1")));

    assertThat(RequirementIrValidator.validateStructure(document, source).valid()).isTrue();
    assertThat(RequirementIrValidator.validateForApproval(document, source).approvable()).isFalse();
    assertThat(RequirementIrValidator.validateForApproval(document, source).blockingCodes())
        .contains(RequirementIrDocument.IssueCode.MISSING_ACCEPTANCE);
  }

  @Test
  void approvalRejectsStructurallyValidLookingButUnknownCompilerFingerprint() {
    String source = "Implement the order lookup.";
    RequirementNode requirement =
        new RequirementNode(
            "R1",
            source,
            RequirementKind.FUNCTIONAL,
            Origin.USER_STATED,
            RequirementState.EXPLICIT,
            new SourceSpan(0, source.length(), 1, 1, 1, source.length() + 1),
            null,
            null,
            List.of(),
            List.of("T1"));
    RequirementIrDocument document =
        new RequirementIrDocument(
            RequirementIrDocument.CURRENT_SCHEMA_VERSION,
            "c".repeat(64),
            RequirementIrValidator.sha256(source),
            List.of(requirement),
            List.of(),
            List.of(new TaskBinding("T1", List.of("R1"))),
            List.of(),
            Completeness.READY);

    var decision = RequirementIrValidator.validateForApproval(document, source);

    assertThat(decision.approvable()).isFalse();
    assertThat(decision.structuralViolations())
        .extracting(RequirementIrValidator.Violation::code)
        .contains(ViolationCode.INVALID_COMPILER_FINGERPRINT);
  }

  private static RequirementIrDocument document(
      String source, RequirementNode requirement, TaskBinding binding) {
    return new RequirementIrDocument(
        RequirementIrDocument.CURRENT_SCHEMA_VERSION,
        RequirementIrCompiler.COMPILER_FINGERPRINT,
        RequirementIrValidator.sha256(source),
        List.of(requirement),
        List.of(),
        List.of(binding),
        List.of(),
        Completeness.READY);
  }

  private static SourceSpan span(int start, int end) {
    return new SourceSpan(start, end, 1, start + 1, 1, end + 1);
  }
}
