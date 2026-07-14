# Project/provider semantic cohort — 2026-07-15

## Scope

This is a bounded, authenticated project-surface smoke against the current backend
worktree. It exercises provider/model resolution for all active providers and one
read-only project-chat request on the three providers for which a live key was
available. It is evidence of provider plumbing and project-chat behavior, not a
benchmark or a claim of broad coding quality.

Project fixture: `Hostel Eval ANTHROPIC` (`9bd60bd8-a9fa-4175-838e-0515fdc43937`),
with two linked repositories (`hostel-be-v3`, `hostel-fe-v3`). The project provider
was restored to `ANTHROPIC` and chat history was cleared after the cohort.

## Environment and guardrails

- Backend: local Spring profile, port `8191`, current packaged backend tuple.
- Authentication: SUPER_ADMIN credential from the approved local credential file;
  no credential material is included here.
- Prompt was explicitly read-only: summarize the project and list linked repos;
  no architecture regeneration, document generation, edits, shell, or other
  mutating action was requested.
- Live calls were limited to one chat request per provider lane. No Gemini/Sarvam
  or other extra calls were made after the observed failure. Sarvam was not present
  in the active matrix.

## Provider/model resolution (no LLM call)

`PATCH /api/projects/{id}/settings` followed by `GET /api/projects/{id}/chat/info`
returned HTTP 200 for every active provider:

| Provider | Resolved model | Context window | Result |
| --- | --- | ---: | --- |
| Anthropic | `claude-sonnet-5` | 1,048,576 | PASS |
| OpenAI | `gpt-5.4` | 200,000 | PASS |
| Gemini | `gemini-3.5-flash` | 1,048,576 | PASS |
| DeepSeek | `deepseek-v4-pro-native` | 1,048,576 | PASS |
| OpenRouter | `deepseek-v4-pro` | 1,048,576 | PASS |
| Fireworks | `deepseek-v4-pro-fw` | 1,048,576 | PASS |

## Live project-chat results

| Requested project provider | Actual provider/model | HTTP/result | Usage / cost evidence | Finding |
| --- | --- | --- | --- | --- |
| Anthropic | Anthropic / `claude-sonnet-5` | 200, `terminationReason=ok` | 744 input / 444 output; `$0.026262` | PASS. Correctly summarized the project and both linked repos. |
| OpenAI | OpenAI request; failover to Anthropic / `claude-sonnet-5` | 200, `terminationReason=ok` | OpenAI returned 429 `insufficient_quota` after four bounded retries; fallback completed with 132 input / 389 output; `$0.031922` incremental tracker total | PARTIAL. Failover worked and response completed, but OpenAI itself was not quality-verified because its key quota was exhausted. |
| Gemini | Gemini / `gemini-3.5-flash` | Request aborted by harness after repeated tool loop; server recorded `terminationReason=max-rounds` | 45,365 input / 66 output; `$0.065770`; cached-content reads present | FAIL/PARTIAL. Gemini repeatedly called `list_repos`/`get_project_memory` despite the read-only request, never reached a final answer within the six-round cap, and burned substantially more input than Anthropic. |

The OpenAI 429 and Gemini max-rounds details are taken from the local backend log
(`RunCostTracker`/`ProjectChatService` lines for run key
`chat-9bd60bd8-a9fa-4175-838e-0515fdc43937`). No secrets are required to reproduce
the interpretation. The Gemini request was terminated; no request was left running.

## Deterministic project-surface checks

The same authenticated fixture also returned HTTP 200 for the project read surfaces
used by the existing project smoke: project/repository listing, chat info, summary,
architecture/staleness/version reads, memory/ledger, scores, tech-debt, activity,
documents, PRs, workspace health, and contract-coverage routes. These checks prove
transport/access contracts only; they do not prove provider-backed generation.

## Conclusions and follow-up

1. Provider selection and model metadata are wired across all six active providers.
2. Anthropic completed the bounded project-chat task cleanly.
3. OpenAI's fallback path is operational, but OpenAI quality remains unverified until
   its upstream quota is restored.
4. Gemini's project-chat tool loop is a concrete quality/efficiency gap: its model
   selected read tools repeatedly instead of producing a final answer. Add a
   provider-specific loop guard or prompt/tool-choice policy and rerun a k>=3 cohort
   before enabling Gemini as a preferred project-chat route.
5. Architecture/doc generation and edit/test/repair were intentionally not live-run
   here: the fixture repositories are `BOOTSTRAP_FAILED`, and starting those paths
   would clone/build external repos and exceed this bounded diagnostic's evidence
   value. They remain release blockers for broad project/provider quality claims.

## Gemini duplicate-loop regression (current source)

After the duplicate-tool guard was added to `ProjectChatService`, the same read-only
prompt was run once against a freshly packaged current source on isolated port `8192`.
The project provider was set to Gemini for the request and restored to Anthropic
afterwards; chat history was cleared.

- HTTP 200; assistant returned a complete summary and both linked repositories.
- `terminationReason=ok`; the guard activated after round 4, then the no-tools final
  answer turn completed instead of allowing another read loop.
- 33,855 input / 249 output tokens in the service response; five provider calls in
  total (four tool rounds plus the forced final answer); tracker cost `$0.034176`.
- No provider other than Gemini was called for this regression, and the isolated
  JVM was stopped after capture.

This demonstrates a concrete improvement over the earlier Gemini run, which reached
`max-rounds` after six repeated read-tool rounds. It does not establish broad Gemini
quality: the input remained large because the tool-loop transcript is retained, and
only one regression task was run.
