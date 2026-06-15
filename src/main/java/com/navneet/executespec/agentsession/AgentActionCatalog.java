package com.navneet.executespec.agentsession;

import java.util.List;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentActionDescriptor;

import org.springframework.stereotype.Component;

/** Static action catalog used to ground CLI/VS Code intent routing. */
@Component
public class AgentActionCatalog {

  public List<AgentActionDescriptor> list() {
    return List.of(
        new AgentActionDescriptor(
            "chat_answer",
            "Answer a non-mutating question in the current session.",
            false,
            "conversation_usage",
            List.of("message")),
        new AgentActionDescriptor(
            "list_gates", "List plan gates awaiting explicit approval.", false, "none", List.of()),
        new AgentActionDescriptor(
            "inspect_gate",
            "Return the decision card for one awaiting-approval run.",
            false,
            "none",
            List.of("runId")),
        new AgentActionDescriptor(
            "approve_run",
            "Approve a run plan gate. Credits are charged by the existing run approval path.",
            true,
            "run_credit",
            List.of("runId", "creditsConfirmed")),
        new AgentActionDescriptor(
            "reject_run", "Reject a run plan gate.", true, "none", List.of("runId")),
        new AgentActionDescriptor(
            "create_run",
            "Create a gated cloud run from a natural-language spec.",
            true,
            "run_credit_on_plan_approval",
            List.of("spec", "projectId")),
        new AgentActionDescriptor(
            "local_code_task",
            "Ask a local-capable client to handle a coding task in the working tree.",
            true,
            "conversation_usage",
            List.of("spec")),
        new AgentActionDescriptor(
            "resolve_project",
            "Resolve the current repository to a linked ExecuteSpec project.",
            false,
            "none",
            List.of("gitRemote")));
  }
}
