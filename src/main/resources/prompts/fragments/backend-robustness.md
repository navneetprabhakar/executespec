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
  exactly and must not repeat the mutation.
- RUNTIME COMPATIBILITY: if source syntax or dependencies require a minimum runtime version, pin or
  document that minimum next to the exact install/run command (for example Python 3.10+ for `X | Y`
  type unions). The documented command must work on the declared runtime; an undeclared local
  interpreter assumption is not a runnable deliverable.
