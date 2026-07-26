# Explicit cross-repository requirement

Add `GET /api/orders/{orderId}` in the Java service. The TypeScript client must call that exact
route and preserve the `orderId` and `status` response fields. The Python worker is read-only for
this change. Acceptance requires a Java endpoint test and a TypeScript client contract test.
