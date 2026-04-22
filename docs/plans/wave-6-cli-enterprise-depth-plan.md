# Wave 6 — CLI + enterprise depth

> **Window:** 2026-07-02 → 2026-07-15 (2 weeks)
> **Status:** PLANNED. Minimal in-repo surface (audit export endpoint + CSM SLA docs). CLI implementation lives in a separate repo.
> **Origin:** [persona-roadmap-2026-04-22.md §5.6](persona-roadmap-2026-04-22.md).

## W6.1 — ExecuteSpec CLI

**Repo:** `executespec-cli` (to be created), Node.js + `commander`. Ships
via `npm install -g @executespec/cli` + Homebrew formula.

**Anti-scope:** in-monorepo work. This plan doc exists so the CLI repo
can build against a stable API contract.

### v1 commands

```
executespec login                          # OAuth device-code
executespec submit "add POST /api/foo"     # submits run from CWD
executespec status <runId>                 # polls + prints terminal status
executespec pr <runId>                     # opens the PR URL in browser
executespec logs <runId> --tail            # SSE stream to stdout
executespec logout                         # clears cached token
```

### Integration contract (in this monorepo)

- Device-code OAuth: new endpoints `/api/auth/device-code/{start,poll}`
  (shared with [VS Code extension plan](vscode-extension-plan.md))
- `POST /api/runs` with `source=cli`
- `GET /api/runs/{id}/events` SSE (existing, already works for CLI since
  it's token-authenticated via `?token=`)

### Timeline

External. Target v0.1.0 release 2026-07-15 once Wave 6 starts. Not blocking
any in-monorepo work.

## W6.2 — Named CSM tier + support SLA docs

**Content-only.** Add `docs/enterprise-sla.md` (in this commit) specifying:
- P1/P2/P3/P4 severity definitions
- Response-time SLA per severity (ENTERPRISE only — other tiers are
  best-effort via email)
- Named CSM engagement (one CSM per ENTERPRISE customer once the CSM
  role is filled in Q4 2026)
- Escalation path + "call me" after-hours contact
- Service credit schedule for missed uptime SLA (99.9% ENTERPRISE)

**No backend work required** — the SLA doc is the contract; monitoring +
credit issuance is manual for v1.

## W6.3 — Customer logo page + case study

**Content-only.** Placeholders until real customer consent is captured.

- `website/customers.html` — grid of logos (4-6) + 1 case-study link
- `website/case-studies/{company}.html` — 400-word case study with a
  metric ("Team of 8 shipped 40% more features after adopting
  ExecuteSpec") and an embedded demo video

**Prerequisite:** recorded or signed consent from at least 3 customers.
External work. Stubs in this wave; actual content ships when consent
arrives.

## W6.4 — Audit log export (ENTERPRISE only)

**Backend work (in this commit as plan doc; implementation follow-up):**

- `GET /api/admin/audit/export?since=<iso>&until=<iso>&format=csv`
  (ORG_ADMIN + tier=ENTERPRISE)
- Streams `activity_audit` rows for the caller's org as RFC 4180 CSV
- Rate-limited: 1 export per hour per org

**Schema:** no changes — `activity_audit` already has everything we need.

**Frontend:** `OrgAdminDashboard` adds a "Download audit log" button
visible to ENTERPRISE ORG_ADMINs.

## What's committed in Wave 6 (first pass)

- This plan doc
- `docs/enterprise-sla.md` (new)
- Stub plan: the CLI repo + customer content are external

Full implementation (audit CSV endpoint + CLI repo + case studies) is
incremental during the Wave 6 window.

## Timeline

| Day | Work |
|---|---|
| 1 | This plan doc + `docs/enterprise-sla.md` (shipped) |
| 2-4 | `executespec-cli` repo v0.1.0 (external) |
| 5 | `GET /api/admin/audit/export?format=csv` endpoint |
| 6 | OrgAdminDashboard "Download audit log" button |
| 7 | Customer case-study template + case study #1 (assumes consent) |
| 8 | `website/customers.html` + case-study page #1 |
| 9 | End-to-end smoke: CLI against `dev.executespec.ai`, CSV export on
     a sandbox org |
| 10 | Buffer + commit summary |

## Success criteria

- [ ] `npm install -g @executespec/cli` works; `executespec login` +
  `executespec submit` complete end-to-end against dev
- [ ] ENTERPRISE customer can download a 30-day audit log CSV from
  `/admin/analytics` in under 5 clicks
- [ ] `docs/enterprise-sla.md` referenced from the enterprise one-pager
- [ ] At least 1 real customer logo on `website/customers.html` (pending
  consent)

## Non-goals

- CLI GUI / TUI wizard (v1 is command-line only)
- Multi-org CSV export (single-org per call)
- Automated service-credit issuance for SLA misses (manual for v1)
- Real-time SLA dashboard for customers (wait until Wave 7 / post-roadmap)
