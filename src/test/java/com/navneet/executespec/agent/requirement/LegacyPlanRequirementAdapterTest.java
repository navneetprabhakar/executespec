package com.navneet.executespec.agent.requirement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.RequirementSpec;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.IssueCode;
import com.navneet.executespec.agent.requirement.RequirementIrValidator.ViolationCode;

import org.junit.jupiter.api.Test;

class LegacyPlanRequirementAdapterTest {
  private final RequirementIrCompiler compiler = new RequirementIrCompiler();
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void compilationDoesNotMutateOrReserializeLegacyPlanContract() throws Exception {
    String source = "Return 404 when an order is absent.";
    Plan plan = plan(List.of(requirement("R7", source)), List.of(task("T9", List.of("R7"))));
    String before = mapper.writeValueAsString(plan);

    RequirementIrDocument document = compiler.compile(source, plan);

    assertThat(mapper.writeValueAsString(plan)).isEqualTo(before);
    assertThat(document.requirements())
        .extracting(RequirementIrDocument.RequirementNode::id)
        .containsExactly("R7");
    assertThat(document.taskBindings())
        .containsExactly(new RequirementIrDocument.TaskBinding("T9", List.of("R7")));
  }

  @Test
  void plannerRequirementWithoutExactSourceIsExplicitlyInferredAndBlocked() {
    String source = "Create an order lookup.";
    Plan plan =
        plan(
            List.of(requirement("R1", "Create a dashboard with automatic retries.")),
            List.of(task("T1", List.of("R1"))));

    RequirementIrDocument document = compiler.compile(source, plan);

    assertThat(document.requirements())
        .filteredOn(requirement -> requirement.id().equals("R1"))
        .allMatch(
            requirement ->
                requirement.origin() == RequirementIrDocument.Origin.INFERRED
                    && requirement.sourceSpan() == null
                    && requirement.inferenceRationale() != null);
    assertThat(document.issues())
        .extracting(RequirementIrDocument.RequirementIssue::code)
        .contains(IssueCode.SOURCE_PROVENANCE_MISSING, IssueCode.UNCOVERED_SOURCE_CLAUSE);
    assertThat(RequirementIrValidator.validateForApproval(document, source).approvable()).isFalse();
  }

  @Test
  void unknownLegacyTaskReferenceIsNeverRepairedOrInvented() {
    String source = "Create an order lookup.";
    Plan plan = plan(List.of(requirement("R1", source)), List.of(task("T1", List.of("R404"))));

    RequirementIrDocument document = compiler.compile(source, plan);
    var validation = RequirementIrValidator.validateStructure(document, source);

    assertThat(validation.valid()).isFalse();
    assertThat(validation.violations())
        .extracting(violation -> violation.code())
        .contains(ViolationCode.UNKNOWN_REQUIREMENT_REFERENCE);
    assertThat(document.requirements())
        .extracting(RequirementIrDocument.RequirementNode::id)
        .containsExactly("R1");
  }

  private static Plan plan(List<RequirementSpec> requirements, List<PlannedTask> tasks) {
    return Plan.builder().requirements(requirements).tasks(tasks).build();
  }

  private static RequirementSpec requirement(String id, String text) {
    return new RequirementSpec(id, text, RequirementSpec.RequirementKind.FUNCTIONAL);
  }

  private static PlannedTask task(String id, List<String> requirements) {
    return PlannedTask.builder()
        .id(id)
        .title(id)
        .description("Implement " + id)
        .meetsRequirements(requirements)
        .build();
  }
}
