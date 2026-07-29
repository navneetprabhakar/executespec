# Ambiguity fixture

When an order is unavailable, show the normal fallback and retry when appropriate.

The source does not define “unavailable”, the fallback representation, the retry trigger, retry
count, or backoff. These must remain explicit ambiguities until a human or authoritative source
resolves them.
