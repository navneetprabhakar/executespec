# Scaling codemap + architecture docs: layered hierarchy vs. RAG (2026-05-25)

> Decision question: when a project's codemap + architecture doc get huge (large multi-repo
> enterprise codebases), do we (A) produce a **3-layer abstracted hierarchy** (read L1; drill to L2,
> then L3 for more detail), or (B) go **RAG-based** (embed the codemap/arch, retrieve relevant chunks
> per query)? **Recommendation: layered hierarchy as the backbone; RAG only as an optional leaf-level
> retrieval accelerator — not the primary mechanism.**

## TL;DR

**Build the 3-layer hierarchy. It is the better primary abstraction for *both* the codemap and the
architecture doc.** RAG is the wrong primary because the architecture doc's whole job is a *coherent
mental model*, and RAG returns disconnected fragments. Hierarchy gives you RAG's scalability benefit
(you never load the whole thing — you load L1 and drill on demand) **plus** coherence, determinism,
a hard token budget, and zero vector-infra fragility. Add code-aware RAG later as a *complement* for
one specific job (PATCH/worker localization on a pointed query), and only after the existing RAG
fragility is fixed.

## Why hierarchy is the backbone (6 reasons, ExecuteSpec-specific)

1. **It matches the consumers.** The planner decomposes a spec against a *whole-system* view (L1–L2);
   a human reads the arch doc top-down. RAG cannot answer "what is the overall architecture" — it
   hands back fragments with no spine. The arch doc's primary deliverable is the spine.
2. **Determinism + auditability.** Everything else in the product leans on deterministic, reproducible
   behavior (verified score, the enterprise-readiness story, plan-gate review). Hierarchical injection
   is deterministic — L1 is the same bounded block every run. RAG retrieval is non-deterministic
   (embedding model + query phrasing → different chunks), so the same spec could get a different plan
   context run-to-run. That undermines reproducibility of the plan.
3. **Bounded, predictable token cost.** L1 is a fixed budget; you drill only the nodes a task touches.
   RAG's relevance is variable — you either over-retrieve (cost) or under-retrieve (silently miss the
   one file that mattered). Hierarchy gives a hard ceiling per layer.
4. **No new fragility.** The audit already flagged the current RAG path as fragile: OpenAI-only
   embeddings (BYOK-no-OpenAI orgs break), binary-corruption on upload, ivfflat built on an empty
   table → degraded recall. A *code-aware* RAG would multiply that surface. Hierarchy is structured
   text generation + storage — provider-neutral, no vector DB, no recall tuning.
5. **It's the industry abstraction.** The C4 model (Context → Container → Component → Code) is exactly
   a layered hierarchy, because that is how humans reason about systems at scale. We'd be adopting a
   proven structure, not inventing one. RAG-over-architecture has no equivalent canon.
6. **It's an extension of what already works, not a new subsystem.** The 3-step pipeline already emits
   file→symbol codemaps + per-repo HLD/LLD + cross-repo synthesis. Layering is a roll-up on top of
   that. Code-aware RAG is a brand-new subsystem (semantic code chunking + code embeddings + a second
   retrieval path to maintain).

## Where RAG genuinely wins (the honest counter-point)

- **Pointed localization on a specific query** — "where is the off-by-one in the auth refresh path?"
  Semantic retrieval can jump straight to the relevant code when you *don't know which subtree to
  drill*. This is the worker/PATCH-localization case, not the planner case.
- **Codebases so large that even L1 is big** (thousands of modules) — RAG can pre-filter to the
  relevant subtree before you even render L1.

Both are **leaf-level retrieval** problems, not "understand the system" problems — i.e. RAG belongs
*under* the hierarchy, accelerating drill-down, not replacing it. And note: our SWE-bench result
showed **88% localization blind** (no RAG at all), so localization is not the bottleneck that forces
us into a vector subsystem today.

## The concrete 3-layer shape

**Codemap**
| Layer | Content | Budget | Consumer |
|---|---|---|---|
| L1 | repo → package/module inventory + 1-line purpose each | always-inject, small | planner (scope), human |
| L2 | module → file → symbol **names** (today's codemap granularity) | drill the touched modules | planner detail, worker |
| L3 | symbol → **signature + callgraph edges + def-site** (the granularity the audit said is missing) | drill the touched symbols | worker / PATCH localization |

**Architecture doc** (maps 1:1 onto C4)
| Layer | C4 level | Content |
|---|---|---|
| L1 | Context / Container | cross-repo synthesis overview — the system spine |
| L2 | Component | per-repo HLD — components + responsibilities + interfaces |
| L3 | Code | per-component LLD — class/sequence detail |

## The mechanism that actually makes it scale: **addressable nodes + hierarchical retrieval**

The win is not "3 static documents." It is: **L1 carries stable node IDs, and the agent (or a cheap
pre-pass) requests deeper layers only for the nodes relevant to its task.**

- Inject L1 (bounded) into the planner/worker/PATCH prompt with node IDs.
- A cheap selector (heuristic on the spec's touched-files, or one lightweight LLM call) picks the
  relevant L1/L2 nodes → expand those to L2/L3 → inject only those.
- The drill path is **explicit and logged** — so it's auditable and reproducible, unlike an opaque
  vector top-k. This is "hierarchical retrieval": you get RAG's "only fetch what's relevant" without
  RAG's non-determinism or vector infra.

This is also the clean home for the **codemap-consumption fix** we just shipped (RL-2026-05-035): the
bounded codemap block we inject into the planner/PATCH is effectively today's L2 — layering formalizes
it and lets it scale past the current hard char cap by drilling instead of truncating.

## Recommendation + sequencing

1. **Build the layered hierarchy (codemap L1–L3 + arch L1–L3 on the C4 mapping).** Backbone first.
2. **Add addressable node IDs + a cheap drill selector** (heuristic first; one lightweight LLM call if
   needed). This is what converts "3 docs" into "handles scale."
3. **Defer code-aware RAG to a later, optional leaf accelerator** for PATCH localization — and only
   after fixing the existing RAG fragility (provider-neutral embeddings, binary handling, index on a
   populated table). Gate it on localization data showing the hierarchy's drill isn't precise enough.

**Net:** hierarchy is strictly better as the primary for our consumers (planner + human + auditable
plan), and it subsumes RAG's scalability benefit via hierarchical retrieval. RAG stays in the toolbox
for one narrow, later, leaf-level job — not as the architecture's backbone.
