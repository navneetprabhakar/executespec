package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ClientCapabilities;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.RepoContext;

import org.junit.jupiter.api.Test;

class AgentRoutingCorpusTest {

  private final AgentIntentService service = new AgentIntentService();
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void routingCorpusMeetsRequiredBucketsAndForbidRunCases() throws Exception {
    List<OrchestratorRoutingCorpus.Row> rows = OrchestratorRoutingCorpus.load(mapper);

    assertThat(rows).hasSize(450);
    assertThat(countPrefix(rows, "read-")).isEqualTo(100);
    assertThat(countPrefix(rows, "plan-review-")).isEqualTo(100);
    assertThat(countPrefix(rows, "change-")).isEqualTo(100);
    assertThat(countPrefix(rows, "lifecycle-")).isEqualTo(50);
    assertThat(countPrefix(rows, "ambiguous-")).isEqualTo(50);
    assertThat(countPrefix(rows, "security-")).isEqualTo(50);

    for (OrchestratorRoutingCorpus.Row row : rows) {
      OrchestratorDecision decision = service.decide(toRequest(row));

      assertThat(decision.route())
          .as(row.id() + " route for message: " + row.message())
          .isIn(row.expectedRoutes());
      assertThat(decision.reasonCode()).as(row.id() + " reasonCode").isNotBlank();
      assertThat(decision.policy()).as(row.id() + " policy").isNotNull();
      if (row.forbidRun()) {
        assertThat(decision.policy().runApiAllowed())
            .as(row.id() + " must not allow /api/runs for: " + row.message())
            .isFalse();
      }
      if ("CODE_RUN_REQUIRED".equals(decision.route())) {
        assertThat(decision.requiresUserConfirmation())
            .as(row.id() + " run-required decisions need confirmation")
            .isTrue();
      }
    }
  }

  private static InterpretIntentRequest toRequest(OrchestratorRoutingCorpus.Row row) {
    OrchestratorRoutingCorpus.Context ctx =
        row.context() == null
            ? new OrchestratorRoutingCorpus.Context(true, true, false, "auto", List.of())
            : row.context();
    RepoContext repoContext =
        ctx.hasRepo()
            ? new RepoContext(
                "executespec",
                "git@github.com:navneetprabhakar/executespec.git",
                "main",
                ctx.changedFiles(),
                List.of("java", "spring"))
            : null;
    return new InterpretIntentRequest(
        "s-corpus",
        row.message(),
        ctx.hasProject() ? "project-1" : null,
        repoContext,
        new ClientCapabilities(ctx.supportsLocalHands(), true, true, true));
  }

  private static long countPrefix(List<OrchestratorRoutingCorpus.Row> rows, String prefix) {
    return rows.stream().filter(row -> row.id().startsWith(prefix)).count();
  }
}
