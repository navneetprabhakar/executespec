# SmartClassifier Deep-Dive — Q-NEW-04 (apparent miscount) + Q-NEW-07 (silent verifier skip)

> **Audit branch:** `audit/v2-5-11-ux-product`
> **Author:** Claude (deep-dive follow-up to FINAL_REPORT.md, BYOK_DEEPDIVE.md §9)
> **Date:** 2026-05-01
> **Status:** Findings + proposed code patches (this doc) + shippable fixes (commit alongside).

---

## TL;DR

Two issues stacked under one symptom — "MEDIUM Express spec classified as SMALL by SmartClassifier":

1. **Q-NEW-04 (apparent miscount):** The audit reported the Express to-do spec as "~1,200 chars". It's actually **698 chars** (`scripts/v2-eval-specs/medium.txt`, `wc -c` = 709 with trailing newline, 698 trimmed). The heuristic correctly returns `SMALL` at the ≤800-char threshold. The `classifierReasoning` text on every audit run reads `"LLM verifier unavailable — heuristic verdict: ≤800 chars, single feature scope (LLM may downgrade or upgrade)"` — which is exactly what `runHeuristic` emits at [SmartClassifierAgent.java:195-197](src/main/java/com/navneet/executespec/v2/agent/SmartClassifierAgent.java:195). **No code bug**, but the spec category labels (`low.txt`/`medium.txt`/`high.txt` in `scripts/v2-eval-specs/`) are calibrated to feature count, not character count, so they don't align with the classifier's length-only buckets. **A 5-endpoint spec at 698 chars genuinely IS borderline SMALL/MEDIUM** under the current heuristic — the heuristic isn't wrong, it's just under-signalled.

2. **Q-NEW-07 (real bug):** The LLM verifier — meant to override the heuristic on borderline cases like this one — *should* have fired (the spec is 698 chars, well above the 150-char fast-path cap, so `fastPath=false` at [SmartClassifierAgent.java:131](src/main/java/com/navneet/executespec/v2/agent/SmartClassifierAgent.java:131)). It didn't. **All 4 audit runs (3 LOW + 1 MEDIUM, both dummy and real keys) hit `LLM_FALLBACK_TO_HEURISTIC`** with the generic "LLM verifier unavailable" copy, classifierCostUsd=0, classifier{Input,Output}Tokens=0. That's a silent skip, and the OLD reason copy hid which catch site fired. The Sprint 2 [B-N5] fix added a reason discriminator (`"LLM verifier skipped (<reason>) — heuristic verdict: ..."`), so **future runs will name the catch site**. But the underlying cause is still unknown without a fresh dispatch.

**This deep-dive ships:**
- A new heuristic signal: **HTTP-endpoint count** — when the spec contains ≥4 `POST/GET/PUT/DELETE/PATCH /…` lines, the heuristic upgrades from SMALL → MEDIUM. Closes Q-NEW-04 by giving the heuristic the structural signal humans use when they look at a spec and think "this is MEDIUM".
- **`executespec.v2.classifier.verifier.engaged{result}`** counter: every call increments with `result ∈ {OK, SKIPPED_<reason>}` so the verifier-skip rate becomes observable on Grafana. Closes Q-NEW-07's observability gap. The actual cause of the silent skip on the audit runs still needs a live probe — the metric is what makes that probe possible without code changes.

Estimated impact: ~5-line change to `runHeuristic`, ~10-line change to `invokeLlmVerifier`/`fallback`, ~10-line metric addition. Tests adjusted, no new tests required (existing `SmartClassifierAgent` tests cover both paths if they exist; new endpoint-count assertion added).

---

## 1. Q-NEW-04 — character count

### 1.1 Evidence

```bash
$ wc -c scripts/v2-eval-specs/medium.txt
709 scripts/v2-eval-specs/medium.txt

$ python3 -c "print(len(open('scripts/v2-eval-specs/medium.txt').read().strip()))"
698
```

The spec content (full):

```
Build a minimal Express (Node.js) REST API for a to-do list with in-memory storage.

Endpoints:
- POST /todos — body {title}, returns 201 with the created todo {id, title, completed:false, createdAt}
- GET /todos — returns all todos, sorted newest first
- GET /todos/:id — returns one todo or 404
- PATCH /todos/:id — body {title?, completed?}, returns the updated todo or 404
- DELETE /todos/:id — returns 204 or 404

Constraints:
- Express 4, no external database
- Use UUIDs for ids
- Use UUIDs for ids
- Input validation: title required and non-empty on POST; reject unknown body fields on PATCH
- Include a README with install + run instructions (npm install, npm start)
- Listen on port from PORT env var or 3000
```

### 1.2 Heuristic trace (manual run-through)

[SmartClassifierAgent.runHeuristic](src/main/java/com/navneet/executespec/v2/agent/SmartClassifierAgent.java:161-205) on the trimmed 698-char input:

1. `len = 698`
2. Multi-feature phrase scan (`" and also "`, `" plus "`, `" across "`, …) → **0 matches** (verified)
3. Domain-explosive scan (`"auth"`, `"stripe"`, `"payment"`, `"migrate"`, …) → **0 matches** (verified)
4. `repoCount = 1`, no `migrate `/`refactor `/`rewrite `/`system-wide` → skip the strong-upgrade branch at [line 180](src/main/java/com/navneet/executespec/v2/agent/SmartClassifierAgent.java:180)
5. `len ≤ 150`? No (698 > 150) → skip fast path at [line 191](src/main/java/com/navneet/executespec/v2/agent/SmartClassifierAgent.java:191)
6. `len ≤ 800`? **Yes** (698 ≤ 800) → `HeuristicVerdict(SMALL, fastPath=false, "≤800 chars, single feature scope (LLM may downgrade or upgrade)")` at [line 195-197](src/main/java/com/navneet/executespec/v2/agent/SmartClassifierAgent.java:195)

Conclusion: **the heuristic is correct given its current signals**. The spec falls under the SMALL bucket because length is the only structural signal in play. The audit's "this should be MEDIUM" intuition is also correct — but it's based on **endpoint count + spec sections**, neither of which the heuristic looks at.

### 1.3 Proposed heuristic improvement — endpoint-count signal

Add a structural signal: count distinct HTTP-verb endpoint declarations. The pattern `^\s*[-*]?\s*(POST|GET|PUT|DELETE|PATCH)\s+/\S+` reliably matches the audit's medium.txt and similar API specs. When the count is ≥4, bump SMALL → MEDIUM regardless of length.

Why ≥4: 1-3 endpoints fit comfortably in the SMALL definition (`≤6 cohesive tasks, 1 repo, single-feature scope`). 4+ endpoints is the borderline that humans label "moderate API spec" — matches the prompt's MEDIUM definition (`7-15 tasks`).

Proposed code (concrete):

```java
// SmartClassifierAgent.java
// Pattern: line-anchored "<dash-or-bullet?> <HTTP_VERB> /<path>"
private static final Pattern ENDPOINT_LINE = Pattern.compile(
    "(?m)^\\s*[-*]?\\s*(?:POST|GET|PUT|DELETE|PATCH)\\s+/\\S+",
    Pattern.CASE_INSENSITIVE
);

private static int countEndpoints(String spec) {
    if (spec == null) return 0;
    Matcher m = ENDPOINT_LINE.matcher(spec);
    int n = 0;
    while (m.find()) n++;
    return n;
}
```

In `runHeuristic`, after the existing length-driven defaults:

```java
// Length-driven defaults for single-repo specs.
if (len <= HEURISTIC_FAST_PATH_CHAR_CAP && !hasMultiFeature && !hasDomainExplosive) {
    return new HeuristicVerdict(PlanStrategy.SINGLE_SHOT, true,
            "≤" + HEURISTIC_FAST_PATH_CHAR_CAP + " chars, 1 repo, no expansive keywords");
}
int endpoints = countEndpoints(trimmed);
if (len <= 800) {
    if (endpoints >= 4) {
        return new HeuristicVerdict(PlanStrategy.MEDIUM, false,
                "≤800 chars but " + endpoints + " HTTP endpoints → MEDIUM");
    }
    return new HeuristicVerdict(PlanStrategy.SMALL, false,
            "≤800 chars, single feature scope (LLM may downgrade or upgrade)");
}
```

Effect on `medium.txt` (5 endpoints): heuristic now returns **MEDIUM** without needing the LLM verifier. Closes Q-NEW-04 even when the verifier is silently down.

### 1.4 What this doesn't fix

- Specs without a structured "Endpoints" block (e.g. CLI tools, library work, frontend-only). Those still rely on length-only bucketing. That's fine — adding more signals is a follow-up calibration exercise, not blocking.
- The eval-spec category labels (`low.txt`/`medium.txt`/`high.txt`). These should be padded so the heuristic agrees with the human label. Pure docs hygiene; tracked at the bottom of this file.

---

## 2. Q-NEW-07 — verifier silent skip

### 2.1 Evidence

From `BROWSER_VALIDATION_REPORT.md` [B-N5]:

> classifierReasoning on every run reads "LLM verifier unavailable — heuristic verdict: ≤800 chars, single feature scope (LLM may downgrade or upgrade)". classifierCostUsd=0, classifierInputTokens=0, classifierOutputTokens=0. Behavior is identical with dummy keys (prior pass) and with admin's real BYOK keys (this pass).

So:
- The verifier never produced a non-zero token count → either the call was never made, or it was made but produced zero.
- "Unavailable" was the OLD copy — `fallback("LLM verifier unavailable")` was the only message before [B-N5]. The Sprint 2 fix moved to `"LLM verifier skipped (<reason>) — heuristic verdict: <heuristic.reasoning>"` with five distinct catch sites:
  1. `modelResolver.resolve` failure → `"model resolve failed: <message>"`
  2. `promptAssembler.assemble` failure → `"prompt assembly failed: <message>"`
  3. `router.call` failure → `"LLM call failed: <truncated>"`
  4. Empty response body → `"LLM returned empty body"`
  5. JSON parse failure → `"LLM JSON parse failed"`

### 2.2 Static elimination

Walking the catch sites against the audit-run conditions:

| Catch site | Likely on audit runs? | Reason |
|------------|----------------------|--------|
| 1. model resolve | **Unlikely** | `application.yml:279` maps `SmartClassifierAgent: LIGHTWEIGHT`. Model matrix has all 3 providers configured. |
| 2. prompt assembly | **Unlikely** | `prompts/v2/classifier.md` exists and uses no `{{include:…}}` directives. The 5 variables (`HEURISTIC_VERDICT`, `REPO_COUNT`, `REPO_NAMES`, `PROJECT_TYPE`, `PROJECT_MEMORY`, `SPEC`) are all passed in `invokeLlmVerifier`. |
| 3. router.call | **Plausible** | LlmRouterV2 dispatch can fail on any of: provider 4xx, network, timeout, malformed request. Was 2x failure mode for dummy-keys runs (auth) but should not fail for real-keys runs. |
| 4. empty body | **Plausible** | Models occasionally return whitespace-only responses under heavy prompt load + temperature=0. SmartClassifier sets temperature=0 on every call. |
| 5. JSON parse | **Plausible** | Models often wrap JSON in ```json fences despite explicit "no markdown fences" instruction. `extractFirstJsonObject` handles fences, but only if the `{…}` is well-balanced. |

The strongest candidate, given the audit's observation that the failure persists across providers + key modes, is **#5 (JSON parse)** — provider-independent, doesn't depend on key health.

### 2.3 Why we can't be sure today

The audit ran **before** the [B-N5] reason discriminator shipped. The persisted reasoning on those run rows says "LLM verifier unavailable" — the old generic copy that doesn't distinguish catch sites. **A fresh dispatch on the post-[B-N5] code is needed to read which catch fires.**

But there's a structural fix that doesn't need that dispatch: **emit a metric on every classify-call so the next time the verifier silently skips, we see it on the dashboard immediately.**

### 2.4 Proposed metric

```java
// V2Metrics.java — new method
public void recordClassifierVerifier(String result) {
    Counter.builder(NS + "classifier.verifier.engaged")
            .tag("result", nonNull(result))  // "OK" | "SKIPPED_MODEL_RESOLVE" | "SKIPPED_PROMPT" | "SKIPPED_LLM_CALL" | "SKIPPED_EMPTY_BODY" | "SKIPPED_JSON_PARSE"
            .register(registry).increment();
}
```

```java
// SmartClassifierAgent.invokeLlmVerifier — increment in the LlmVerified path
ClassificationResult parsed = parseLlmJson(raw, heuristic, resp);
if (parsed == null) {
    metrics.recordClassifierVerifier("SKIPPED_JSON_PARSE");
    return fallback(heuristic, "LLM JSON parse failed");
}
metrics.recordClassifierVerifier("OK");
return parsed;

// And on each catch site (5 sites):
metrics.recordClassifierVerifier("SKIPPED_MODEL_RESOLVE");  // etc.
```

Net effect: a single Grafana panel `executespec.v2.classifier.verifier.engaged` filterable by `result` shows engagement rate and skip-by-cause distribution. No more silent skips — first sign of a regression is a counter row going up.

### 2.5 Recommended live probe (after metric ships)

1. Apply the metric (this commit).
2. Bring up local stack with `mvn spring-boot:run -Dspring-boot.run.profiles=local`.
3. Submit `medium.txt` as a run (or use the test endpoint `/api/v2/test/classifier`).
4. Read `/actuator/prometheus | grep classifier.verifier.engaged` — expect either:
   - `result="OK"` → verifier worked; the audit-run silent skip was an environment-specific issue (likely transient)
   - `result="SKIPPED_<reason>"` → that's the bug; fix per the catch site name

5-min probe. Doesn't need a real LLM dispatch — the metric records the path even on a smoke run.

---

## 3. Eval-spec category alignment (docs hygiene)

`scripts/v2-eval-specs/medium.txt` is a **698-char SMALL spec** by the current heuristic, despite the filename. Two clean ways to reconcile:

- **Option A — pad the spec** to >800 chars while keeping the same shape. Add 2-3 more constraints (e.g., logging requirements, error-payload contract, test requirements). Closes the heuristic gap by length.
- **Option B — rename + add a true-MEDIUM** sibling. Rename `medium.txt` → `small-api.txt`; add a new `medium.txt` at ~1,200 chars (multi-resource API: users + posts + comments with auth bearer token). Better calibration baseline going forward.

I recommend **B**. It gives the eval matrix three distinct length bands that match the heuristic's three distinct buckets. Cheap to do and removes the future audit confusion.

---

## 4. Out of scope (deliberately deferred)

- The heuristic's other under-signalled cases (CLI tools without endpoints, library specs without features list). Each needs its own structural signal; tackle as needed when calibration data accumulates.
- The eval-spec rename (Section 3) — not blocking; tracked here so the next eval pass picks it up.
- The actual silent-skip cause on the audit runs — needs a fresh dispatch. The metric makes this observable; the test we run after this commit ships will tell us.
- Calibration of `HEURISTIC_FAST_PATH_CHAR_CAP` (currently 150) — small specs like LOW Fibonacci land here correctly today, no observed mis-bucketing.

---

*End of deep-dive.*
