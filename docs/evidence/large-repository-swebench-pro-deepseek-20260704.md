# Large-repository outcome evidence — SWE-bench Pro sample-20

## Scope

This is the first reproducible large-repository outcome artifact available for
the ExecuteSpec harness. It uses a fixed 20-instance slice of SWE-bench Pro,
the ExecuteSpec benchmark runner, DeepSeek native/flash routes, and an external
test evaluator. It is historical evidence from 2026-07-04, not a claim that the
current release tuple has been re-run unchanged.

| Field | Value |
| --- | --- |
| Dataset | `~/swebench-bench/pro/swebench_pro_sample20.json` |
| Dataset SHA-256 | `42893cf327246e2306daa3c79c050df56dd845f55542b4d3089bf4d1122cccc9` |
| Generation manifest | `~/swebench-bench/keval/pro20-postfix-flash/pass1.manifest.jsonl` |
| Evaluator outputs | `~/swebench-bench/keval/pro20-postfix-flash/score-*/eval_results.json` |
| Model | DeepSeek V4 Flash Native (`deepseek-v4-flash-native`) |
| Instances | 20 |
| Generation cost | `$0.365` |
| Generation wall time | `13,279.9s` (~3h41m) |
| Patch-producing runs | 20/20 |

## Outcome

The external evaluator marked **10/20 resolved (50%)**. The result is computed
from the 20 per-instance boolean `eval_results.json` files, not from the
ExecuteSpec headline score. The slice includes Ansible, Future Architect/Vuls,
Flipt, Navidrome, Element Web, ProtonMail WebClients, QtWebBrowser,
Internet Archive OpenLibrary, NodeBB, Tutao/Tutanota, and Teleport repositories.

Every generation row was `COMPLETED` with a non-empty patch. The evaluator ran
task-specific tests; for example the Ansible output contains a passing
`test_get_sysinfo_facts` test record. Both resolved and unresolved examples are
retained in the score directories, so this is not a success-only sample.

## Interpretation

- This closes the previous “no large-repository outcome evidence” gap with a
  real OSS benchmark outcome and a reproducible external-verifier trail.
- It does **not** establish SOTA parity, current-tuple parity, or production
  quality. The source commit/configuration tuple for the historical July-04
  run is not captured in the manifest and must be frozen in the next rerun.
- The 50% resolved rate is materially below a frontier-release claim. It should
  be used as a baseline for prompt/model/routing work, not marketing copy.
- A current-tuple rerun should preserve the dataset SHA, use the same task
  slice, record backend/CLI tree SHAs, and attach per-task trajectory plus
  independent verifier outputs before this gate can be called closed.
