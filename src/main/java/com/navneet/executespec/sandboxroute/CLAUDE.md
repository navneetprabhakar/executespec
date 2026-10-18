# Sandboxed command and verification routing

Normal RUN commands and exact-generation VERIFICATION commands use separate durable sandboxes.
A verification session acquires its fenced lease before exposing its owned workspace and preserves
that owner/token for its entire lifetime: materialization, reads, provider calls and commands.

`VerificationLeaseHeartbeat` owns a dedicated daemon clock, independent from scheduled cleanup and
billing work. It captures tracing context, survives failed ticks, and quiesces on Spring shutdown.
`executespec.sandbox.verification-heartbeat-interval-ms` defaults to 10000 (valid range 1..30000).
Renewal never shortens a command's longer lease. A failed renewal/cancellation/expiry permanently
fences the session; it cannot reacquire a new token. Unbound/closed capabilities cannot spend or
read through worker tools. Previously recorded physical command receipts remain readable until
close even if the live action authority is lost.

Cleanup releases the exact owned runtime before deleting its tree; failure preserves the owned
workspace for durable reconciliation. Real PostgreSQL tests cover idle review renewal and hostile
takeover fencing. Runtime profiles, mounts, generation checks and no-host-fallback policy remain
authoritative. No applied migration changes are needed for this lease-lifetime repair.
