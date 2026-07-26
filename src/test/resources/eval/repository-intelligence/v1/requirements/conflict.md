# Conflict fixture

The Java service must expose `GET /api/orders/{orderId}`. The TypeScript client must retrieve the
same order from `POST /api/order/search`. Both clauses are authoritative. The compiler must surface
the route/method conflict and must not select one silently.
