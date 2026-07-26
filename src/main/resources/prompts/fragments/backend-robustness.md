BACKEND ROBUSTNESS — MANDATORY:
- Express/Node.js: Centralized JSON error middleware (err, req, res, next) AND 404 handler
- Python (FastAPI/Flask): Global exception handlers AND 404 handler returning JSON
- Java (Spring Boot): @RestControllerAdvice for centralized JSON error handling AND 404 handler
- Go / Rust: Global JSON error handlers AND 404 handlers
- NETWORK BINDING: ALL servers MUST bind to 0.0.0.0 (not localhost)
- All API responses must be structured JSON, never raw HTML or text
- CONCURRENCY CONTRACTS: when the requirement says atomic, idempotent, overlap-safe, unique, or
  concurrent, a read/check followed by a write is NOT safe by itself. Enforce the invariant at the
  database/transaction boundary (constraint, lock, serializable transaction, or atomic conditional
  mutation appropriate to the datastore) and exercise two callers that are forced through the same
  initial race window. Assert every response and the final persisted state; a lucky parallel test
  without a synchronization barrier is not proof.
- IDEMPOTENCY CONTRACTS: lookup, payload comparison, business mutation, and storage of the first
  response/status/contract headers must commit atomically. A replay must return that stored result
  exactly and must not repeat the mutation. Do not plan a generic middleware that commits a claim,
  runs the handler in a different transaction, and updates the response later: that is not one
  atomic boundary. The application coordinator must share the business transaction. A durable
  claim row needs scoped uniqueness, explicit PROCESSING/COMPLETED lifecycle, and response fields
  that may remain empty until the winner completes; concurrent losers wait/re-read the completed
  winner rather than replaying an unfinished placeholder.
- IDEMPOTENCY RACE OWNERSHIP: the request must win an atomic database claim for the scoped
  idempotency key before performing the business mutation, or acquire an equivalent datastore
  serialization boundary before the initial lookup. Never mutate business state and then hope a
  later unique-key insert succeeds. A concurrent loser must roll back, wait/re-read the committed
  winner, and replay it when the payload matches; constraint/lock/busy exceptions must be mapped to
  the documented response and must never escape as a 5xx. For SQLite, use a real write-transaction
  boundary or claim-row protocol appropriate to WAL/busy-timeout semantics; `SELECT FOR UPDATE` is
  not a lock there.
- CARDINALITY PRESERVATION: explicit “lines”, “items”, “multiple SKUs”, or atomic multi-item
  operations require a parent/child or equivalent collection-capable model. Never collapse that
  contract into one product/quantity field on the parent merely to simplify persistence.
- RUNTIME COMPATIBILITY: if source syntax or dependencies require a minimum runtime version, pin or
  document that minimum next to the exact install/run command (for example Python 3.10+ for `X | Y`
  type unions). The documented command must work on the declared runtime; an undeclared local
  interpreter assumption is not a runnable deliverable.
- SQLITE PORTABILITY: SQLite has no built-in `uuid_generate_v4()`/`func.uuid4()` server function.
  Use a portable application-side UUID default or an explicitly installed SQLite-compatible
  expression, and make migrations plus fresh-start tests exercise the same choice.
- ERROR ENVELOPE: when stable sanitized JSON errors are required, choose one exact envelope and use
  it in handlers, OpenAPI, frontend clients, and black-box tests. Default to
  `{"error":{"code":"STABLE_CODE","message":"sanitized message","details":{}}}`; never let
  framework-default `detail`, a flat string error, and nested test assertions coexist.
