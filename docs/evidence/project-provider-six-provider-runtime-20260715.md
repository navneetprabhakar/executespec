# Six-provider project-chat runtime smoke — 2026-07-15

## Scope

Bounded authenticated smoke against the current packaged backend on an isolated
local port. The request was read-only project summarisation; no run was
submitted and no project/repository settings were changed by the smoke.

| Field | Value |
| --- | --- |
| Backend source tuple | current `codex/executespec-2.0` artifact (`f3f77599c` source lineage) |
| Profile | `local` |
| Principal | SUPER_ADMIN (credential redacted) |
| Project fixture | `Hostel Eval OPENAI`, two linked repositories |
| Provider calls | 6 project-chat turns, one per active provider |
| Recorded spend | approximately `$0.03443` |
| Sarvam | absent from active matrix and not called |

## Results

| Requested provider | Model/route | HTTP | Input / output | Cost | Assessment |
| --- | --- | ---: | ---: | ---: | --- |
| Anthropic | `claude-sonnet-5` | 200 | 3,918 / 98 | `$0.014323` | PASS; coherent read-only summary |
| OpenAI | `gpt-5.4` | 200 | 3,918 / 103 | `$0.005226` | PASS; coherent read-only summary |
| Gemini | `gemini-3.5-flash` | 200 | 31,298 / 495 (420 reasoning) | `$0.010411` | PARTIAL; reached tool-call limit after repeated reads |
| DeepSeek | `deepseek-v4-pro-native` | 200 | 10,467 / 291 | `$0.001091` | PASS; coherent read-only summary |
| OpenRouter | `deepseek-v4-pro` | 200 | 10,596 / 234 | `$0.001237` | PASS; coherent read-only summary |
| Fireworks | `deepseek-v4-pro-fw` | 200 | 10,443 / 169 | `$0.001142` | PASS; coherent read-only summary |

Every turn emitted the expected acknowledgement, route decision, tool/result,
and done events. The route decision was `PLAN_ONLY` with confidence `0.91`.

## Interpretation

- Provider/model resolution and project-chat transport are operational across
  all six active lanes.
- Anthropic, OpenAI, DeepSeek, OpenRouter, and Fireworks produced coherent
  summaries in this one-turn cohort.
- Gemini is not a preferred project-chat route yet: it completed HTTP 200 but
  consumed roughly 3–8x the input of the other lanes and ended with a
  tool-call-limit message rather than a clean answer.
- This is a plumbing/behavior smoke, not a coding-quality benchmark. A repeat
  cohort (at least three tasks/provider) is still required before claiming
  provider parity.

## Reproduction limits

The exact credentials and provider keys are intentionally excluded. The smoke
used the approved local credential environment and was stopped after capture;
only normal chat session/turn usage rows were persisted.
