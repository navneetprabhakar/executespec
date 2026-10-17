# Worker conversation snapshots

`ContextStore` remains a best-effort Redis/disk history cache. `ContextSnapshotCodec` encodes
messages explicitly and includes a nullable `SavedContext.workspaceGenerationAttemptId`.

Generic legacy/PATCH reads keep their existing run/task/TTL identity contract. BUILD smoke retries
must use `loadForWorkspaceAttempt`, which additionally requires the exact current physical attempt
id from the live generation capability. Missing historical ids and snapshots from replaced attempts
are rejected even when run/task/time match. BUILD crash recovery starts a fresh transcript after
restoring the accepted generation; it never replays uncommitted candidate claims.

The snapshot's durable id is evidence, not authority. Provider/tool dispatch still requires the
separate live workspace/LLM capability and database lease checks.
