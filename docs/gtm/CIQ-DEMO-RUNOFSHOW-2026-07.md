# CommerceIQ Demo — Run of Show (2026-07-16)

Context: Gaurav (HOE) + likely 1–3 of his engineers. ~30 min. Their stated pains: **integration test cases** + **token cost**. Product state: end-to-end works but **flaky**. Goal of the meeting: not to dazzle — to earn a **60-day pilot**. A clean, honest, pain-targeted 20 minutes beats a feature tour every time.

---

## The one rule

**Never let them watch something break live.** Flaky + live + in front of the buyer = the 43/100 web-run risk from your own eval, in the worst possible room. The entire run-of-show is built so that even if a live run hiccups, the demo still lands. Three defenses, in order of preference:

1. **Pre-run the hero run before the call.** Have a completed run — spec → plan gate → PR → verification report — already sitting in the UI from 30 min before. You *walk through the real artifacts* (the actual PR, the actual evidence ledger, the actual token ledger). This is the safest and most credible path: it's real output, just not gambled on live.
2. **One live run, narrow and rehearsed.** If you do go live, use the single scenario you've run clean 10+ times this week, on a repo you control, and start it early so it completes while you talk through pre-baked artifacts. Narrate the plan gate live (that part is fast and reliable), let execution happen in the background.
3. **Screen recording fallback.** Have a 3-min recording of a perfect end-to-end run on your desktop, one click away. If anything stalls, "let me show you one I ran earlier on a similar service" — smooth, not apologetic.

Decide which of the three is your primary by demo day. Recommendation given flakiness: **#1 as the spine, #2 as the live moment for the plan gate only, #3 in your back pocket.**

---

## Run of show (~25 min + buffer)

### 0. Before they join (you, 30 min prior)
- Kick off the hero run so a completed PR + report is ready to show.
- Open tabs in order: the completed run detail, the PR on GitHub, the token ledger, a fresh Compose screen for the live plan-gate moment.
- Recording fallback loaded. Phone on silent. Notifications off.

### 1. Frame (2 min) — say the pain back to them
"You told me two things: integration test coverage, and token cost. I built this against exactly those, so I'll show you both on a service that looks like yours — not a toy demo." *(This sentence does more than any feature. It says: I listened, this is for you.)*

### 2. The spec → plan gate, LIVE (4 min) — your most reliable, most differentiated moment
- On a **realistic backend REST service**, type a real spec: e.g. *"Add integration tests for the orders API — cover create, fetch, cancel, and the invalid-quantity error path."*
- Show the plan appear **with the cost estimate**. Stop here and make the point out loud: **"Nothing has been spent yet. I approve the plan and the price before a single token is used. And this is running on DeepSeek — the estimate you're seeing is a fraction of what the same run costs on GPT or Claude."** This hits *both* pains in one screen — tests + cost control — and the plan gate is fast and reliable, so it's safe to do live.
- Approve it. Let execution run in the background while you move to the pre-baked artifacts.

### 3. The verification report + PR (6 min) — the payoff, on pre-run artifacts
- Switch to the already-completed run. Walk the **PR on their-style repo**: real integration tests (RestAssured HTTP against a live app — "not unit tests, actual endpoint tests"), the diff, the build + test results.
- Open the **verification report / evidence ledger.** Make the honest-differentiator point: **"Every line here is labeled — this was deterministically checked, this was model-judged. It's evidence, summarized by a score, not the model grading its own homework."** This is the line that converts skeptical engineers.
- "Your team reviews and merges this like any other PR. No new tool, no retraining." *(Directly answers Gaurav's 'we just trained the team' constraint.)*

### 4. The token ledger (3 min) — nail the cost pain with a number
- Show the run's **token ledger**: estimate → actual → provider cost. Then the comparison: **"This run cost $X on DeepSeek. Same work on GPT-5/Sonnet is 10–20× that. On Terminal-Bench, our full benchmark ran $24 versus $130–$2,000 for leaderboard entries — public run, here's the link."**
- The BYOK point: **"And it's your keys, your billing, zero markup from us. You control the budget at org, team, and run level."**

### 5. Integration tests for THEIR world (3 min) — the co-build hook
- Be honest and specific: "What you saw is HTTP/REST, ready today. I know CommerceIQ runs heavy ETL/data-processing flows — that's where we co-build. Give me one real ETL flow, and in 1–2 weeks your team has an integration-test solution shaped to it, running in your environment." *(MCP/A2A: mention only if genuinely demo-ready by then.)*

### 6. The ask (3 min) — don't leave without it
- "Here's what I'd love: one or two of your teams, 60 days, on your own keys. You run it on real backlog, your team shapes the enterprise controls — budget caps, approval workflows, governance — against CIQ's actual needs. Your engineers get early access and hands-on time with spec-driven workflows. I'm personally involved the whole way."
- Then stop talking. Let him respond.
- Have the **one-page pilot terms** ready to leave behind (CIQ-PILOT-PROPOSAL Part B).

### 7. Close
- Agree the next concrete step: who's the DRI on their side, which repo/flow for the pilot, when you scope it. A demo with no named next step is a lost demo.

---

## Landmines to pre-empt (have the answer ready, don't wait to be asked)

| If they ask / raise | Your move |
|---|---|
| **Sonnet 4.6 benchmark citation** | "GLM 5.1 is on the official TB board at 58.7; Sonnet 4.6's number is from BenchLM's tracking — I'll send both links." Say it *before* they check. |
| **"Is our code safe? / security"** | "Runs on your keys, in your environment; workspace wiped at run end; we never train on your code. Happy to send a security overview." Ask what their security team needs for a scoped non-prod pilot — surface it now, not in week 5. |
| **DeepSeek / Chinese model concern** | "BYOK — you choose the model. Use DeepSeek for cost, or route to Claude/GPT for anything sensitive. Model choice is a checkbox." |
| **"What happens when it fails a task?"** | Show the auto step-up/step-down honestly; and "it tells you at the plan gate what it'll cost to try, you're never surprised." |
| **A live run stalls** | Fallback #3, smoothly: "let me show you one I ran on a similar service earlier" → recording. No apology, no fumbling. |
| **"How mature is this / who else uses it?"** | Honest: "Pre-launch. You'd be a design partner — which is why the pilot is co-build and I'm personally on it. You shape it before anyone else." Honesty is the sale here. |

## What NOT to do
- Don't show the full product surface — you'll hit a rough edge. Show the narrow, rehearsed path.
- Don't oversell parity ("beats GPT-5 at software engineering") — you'll get probed and lose ground. Cost + verification + control is the honest, winning frame.
- Don't demo MCP/A2A unless it's genuinely solid by demo day.
- Don't end on features. End on the pilot ask and a named next step.

## Prep checklist (this week)
1. Lock the hero scenario; run it clean 10+ times on the realistic backend repo.
2. Record the 3-min fallback of a perfect run.
3. Reconcile the Sonnet 4.6 citation; have both links pasted and ready.
4. Finalize the one-page pilot terms (Part B) to leave behind.
5. Confirm ETL co-build scope is a real 1–2 week promise you can keep.
6. Dry-run the whole 25 min out loud, twice, timed.
7. Send the pre-demo agenda note once the slot is confirmed (offer to draft it).
