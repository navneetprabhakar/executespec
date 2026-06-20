# Agent Control Plane + Managed AI Plan

The canonical execution plan is:

[`docs/plans/agent-control-plane-managed-ai-2026-06-19.md`](docs/plans/agent-control-plane-managed-ai-2026-06-19.md)

Terminal work is grouped separately for audit here:

[`docs/plans/terminal-audit-register-2026-06-20.md`](docs/plans/terminal-audit-register-2026-06-20.md)

Use that document as the single source of truth for the launch work that combines:

```text
web agent control plane
agent-session API contract
CLI / VS Code local-hands boundary
terminal audit tags: TERM-AUDIT-*
managed ES AI credits
dynamic model step-up / step-down
per-call raw COGS enforcement
state-transition notifications
adversarial launch verification
```

Execution order is mandatory:

```text
1. Finish backend orchestrator requirements and tests.
2. Execute the web-agent plan from ~/Downloads/executespec-web-agent-plan.md.
3. Execute the managed-AI plan from ~/Downloads/executespec-managed-ai-post-merge-plan.md.
4. Refresh all affected project docs after each completed item, not only at the end.
```

This root file exists only as a discoverable entrypoint. Do not fork the plan here; update the canonical document under `docs/plans/`.
