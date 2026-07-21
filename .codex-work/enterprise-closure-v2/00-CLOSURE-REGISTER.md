# ExecuteSpec Enterprise — Implementation-Ready UX Closure Register

Status: **In execution — not closed**  
Date opened: 2026-07-20  
Product owner: Navneet Prabhakar  
Figma: <https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S>

## Authority

1. `ENTERPRISE-BRAINSTORM-DECISIONS-CLOSED.md`
2. `ENTERPRISE-IMPLEMENTATION-PRD.md`
3. `ENTERPRISE-CODING-AGENT-DELIVERY-GUIDE.md`
4. Domain specifications
5. UX, wireframe, audit, cross-check, and closure-plan documents

This folder is the implementation handoff and closure-evidence layer. It does not supersede the documents above. A conflict is recorded rather than silently normalised.

## Current evidence baseline

- The PRD contains exactly **109 unique stable requirement IDs**.
- The live Figma file contains the expected `Foundations` (`5:2`), `Components` (`5:3`), and `Enterprise Screens` (`5:4`) pages. Figma lazy page loading caused an initial file-level read to expose only the Cover page; direct node lookup confirmed the other pages and audited nodes are intact.
- `Enterprise Screens` currently contains 47 top-level nodes: 45 playable destinations, P00, and one start label.
- Current pack: 2,231 text nodes; 898 at 11px, 636 at 12px, 528 at 13px; 224 reaction sources; 252 instances.
- The local design system has 71 variables, 7 text styles, 2 effect styles, and only 3 component sets (`Button`, `Objective Row`, `Source Row`). This is insufficient for the required state/action/navigation/responsive system.
- The current product code has reusable run, plan-gate, bounded execution, multi-repository, test generation/execution, validation report, evidence sharing, project/RAG/memory, SCM, preview, audit, and model-policy capabilities. It does **not** have a canonical Enterprise Objective/ContextBundle/Epistemic/Decision/Verification domain model.

## Closure decisions

- Retain the approved Objective-centred shell. The explicit PRD §9.1 amendment is in `01-PRD-9.1-NAVIGATION-AMENDMENT.md`.
- Production rollback/remediation is **P2/Conditional**. P0/P1 operations remain read-first.
- Objective-scoped Intelligence is P0. Organisation-wide/cross-team Intelligence is P1.
- Slack query/actions are P1. PagerDuty/ServiceNow workflow depth is P1/Conditional.
- Studio and Enterprise remain separate views.
- Verification remains embedded in Objective, Evidence, Services, PR/CI, CLI/IDE, and Admin; it does not become a seventh global product.

## Required artifact set

| Artifact | Status |
|---|---|
| 109-ID PRD traceability matrix | In progress |
| Screen/state manifest | Pending |
| Component and variant inventory | Pending |
| Tokens and responsive rules | Pending |
| Prototype transition map | Pending |
| Permission/redaction matrix | Pending |
| Action authority/effect/recovery contract | Pending |
| Analytics events | Pending |
| Acceptance tests and 14-scenario closure record | Pending |
| Current/planned/deferred implementation status | In progress |

## Non-negotiable gate

Design-to-code implementation must not begin until every P0 user-facing requirement has a complete surface/state/breakpoint mapping. Enterprise UX must not be labelled closed until the final adversarial validation proves every gate rather than merely finding no obvious issue.
