package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyTextSupport.cleanDescriptionWhitespace;
import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

/** Focused reservation, idempotency, and stock-ledger text normalization. */
final class PlanSafetyReservationTextSupport {
  private PlanSafetyReservationTextSupport() {}

  static String rewriteReservationAggregateSchema(String value) {
    String canonical =
        "### Reservation\n- `id`: primary key\n- `order_id`: FK to Order, not null\n-"
            + " `status`: ACTIVE or RELEASED; default ACTIVE\n- `created_at` and nullable"
            + " `released_at` UTC timestamps\n- owns a"
            + " one-to-many `lines` relationship to ReservationLine\n- enforce at most one ACTIVE"
            + " reservation per order with a SQLite partial unique index on order_id WHERE"
            + " status='ACTIVE'; released history must not block a later reservation\n\n###"
            + " ReservationLine\n-"
            + " `id`: primary key\n- `reservation_id`: FK to Reservation, not null\n-"
            + " `order_line_id`: FK to OrderLine, not null\n- `inventory_id`: FK to Inventory,"
            + " not null\n- `sku`: persisted SKU\n- `quantity`: positive reserved quantity";
    return safe(value)
        .replaceAll("(?is)### Reservation\\s+.*?(?=\\s+## Migration)", canonical + "\n\n")
        .replaceAll(
            "(?is)- \\*\\*Reservation\\*\\*:[\\s\\S]*?(?=\\n- \\*\\*[A-Z]|\\n\\n\\*\\*Migration)",
            "- **Reservation**: parent aggregate with order_id, ACTIVE/RELEASED"
                + " status, timestamps, and ReservationLine children.")
        .replaceAll(
            "(?i)(\\d+\\.\\s+\\*\\*Reservation\\*\\*):[^.\\n]*\\.",
            "$1: id, order_id (FK→orders.id), status (ACTIVE | RELEASED, default ACTIVE),"
                + " created_at, released_at, and ReservationLine children. Add a SQLite partial"
                + " unique index on order_id WHERE status='ACTIVE' so released history does not"
                + " block re-reservation.")
        .replaceAll(
            "(?i)(\\d+\\.\\s+\\*\\*ReservationLine\\*\\*):[^.\\n]*\\.",
            "$1: id, reservation_id (FK→reservations.id), order_line_id (FK→order_lines.id),"
                + " inventory_id (FK→inventory.id), sku, quantity (positive), created_at.");
  }

  static String removeForeignReservationSchemaCorrection(String value) {
    return cleanDescriptionWhitespace(
        safe(value)
            .replaceAll(
                "(?is)\\s*CONTRACT CORRECTION \\(binding schema\\): Order persists status"
                    + " \\((?:default PENDING|string:\\s*PENDING\\s*\\|\\s*RESERVED,"
                    + "\\s*default PENDING)\\) and owns OrderLine rows; Reservation persists"
                    + " order_id(?:, warehouse_id)?, status \\(default ACTIVE\\), and owns"
                    + " ReservationLine rows; Each ReservationLine persists.*?(?=(?:\\n\\n|$))",
                ""));
  }

  static String rewriteReservationAggregateConsumers(String value) {
    return safe(value)
        .replaceAll(
            "(?i)create Reservation rows? \\(one per order line\\)",
            "Create one Reservation parent and ReservationLine children (one child per order line)")
        .replaceAll(
            "(?i)one Reservation row per order line",
            "one Reservation parent with one ReservationLine child per order line")
        .replaceAll(
            "(?i)ReservationResponse\\(id,\\s*order_line_id,\\s*warehouse_id,\\s*"
                + "reserved_quantity,\\s*status\\)",
            "ReservationResponse(id, order_id, status, lines), where each line"
                + " contains reservation_id, order_line_id, inventory_id, sku, and"
                + " reserved_quantity")
        .replaceAll(
            "(?i)Reservation(?:Out|Dto|DTO)\\(id,\\s*order_line_id,\\s*warehouse_id,\\s*"
                + "reserved_quantity,\\s*status\\)",
            "ReservationOut(id, order_id, status, lines), where each line contains"
                + " reservation_id, order_line_id, inventory_id, sku, and reserved_quantity");
  }

  static String normalizeFrontendReservationContract(String value) {
    String normalized =
        safe(value)
            .replace("(PENDING|RESERVED|PENDING)", "(PENDING|RESERVED)")
            .replace("status (PENDING|RESERVED|PENDING)", "status (PENDING|RESERVED)")
            .replace(
                "PENDING=yellow, RESERVED=green, PENDING=gray", "PENDING=yellow, RESERVED=green")
            .replaceAll(
                "(?i)releaseReservation\\(orderId:\\s*number\\)",
                "releaseReservation(reservationId: number)")
            .replaceAll(
                "(?i)Both return the updated Order(?: DTO)?\\.",
                "Both return the Reservation aggregate; after success refresh the Order list to"
                    + " observe its PENDING/RESERVED status.")
            .replaceAll("(?i)releaseReservation\\(orderId\\)", "releaseReservation(reservationId)");
    String contract =
        "FRONTEND/BACKEND RESERVATION CONTRACT (binding): reserve by Order.id and consume the"
            + " returned Reservation aggregate; retain its Reservation.id for"
            + " POST /reservations/{reservation_id}/release. Refresh the Order list after either"
            + " mutation to render Order.status.";
    return normalized.contains(contract) ? normalized : normalized + "\n\n" + contract;
  }

  static String rewriteIdempotencyTransaction(String value) {
    return safe(value)
        .replace(
            "After commit: update the `IdempotencyRecord`",
            "Before the single transaction commits, update the `IdempotencyRecord`")
        .replace(
            "After commit: update the IdempotencyRecord",
            "Before the single transaction commits, update the IdempotencyRecord")
        .replace(
            "After commit: update IdempotencyRecord",
            "Before the single transaction commits, update IdempotencyRecord");
  }

  static String normalizeSqliteIdempotencyAlgorithm(String value) {
    String canonical =
        "SQLITE IDEMPOTENCY ALGORITHM (binding): the endpoint/service—not middleware—starts"
            + " `BEGIN IMMEDIATE` before reading IdempotencyRecord. If a COMPLETED row exists,"
            + " replay its exact status/body for the same request hash or return 409 for a"
            + " different hash. Otherwise insert PROCESSING, execute the guarded business"
            + " mutation, persist the exact COMPLETED response, and commit once. A concurrent"
            + " request blocks at BEGIN"
            + " IMMEDIATE and then re-reads the committed row; never poll for another"
            + " transaction's uncommitted PROCESSING record and never commit a claim separately.";
    String source =
        safe(value)
            .replaceAll("(?im)^SQLITE IDEMPOTENCY ALGORITHM \\(binding\\):[^\\n]*(?:\\R|$)", "")
            .replaceAll(
                "(?i)\\b(insert|create)(\\s+(?:an?|the))?\\s+DRAFT\\s+record\\b",
                "$1$2 PROCESSING record")
            .replaceAll("(?i)(\\bstate\\s*=\\s*['`])DRAFT(['`])", "$1PROCESSING$2")
            .replaceAll(
                "(?i)If a COMPLETED record exists, return the stored response body for"
                    + " (?:the )?matching order_id",
                "If a COMPLETED record exists, return the stored response body for the same"
                    + " request_body_hash")
            .replaceAll(
                "(?i)If COMPLETED with matching order_id",
                "If COMPLETED with the same request_body_hash")
            .replaceAll(
                "(?i)If COMPLETED with different order_id",
                "If COMPLETED with a different request_body_hash")
            .replaceAll("(?i),?\\s*order_id,?\\s+and key\\b", ", key")
            .replaceAll(
                "(?i)If PROCESSING[^.\\n]{0,180}(?:wait|poll)[^.\\n]*",
                "If another request owns PROCESSING, block at BEGIN IMMEDIATE and re-read only"
                    + " after that transaction commits")
            .replaceAll(
                "(?i)(?:the )?losing transaction[^.\\n]{0,120}poll[^.\\n]*",
                "the losing transaction blocks at BEGIN IMMEDIATE and re-reads the committed"
                    + " record")
            .trim();
    return source.contains(canonical) ? source : source + "\n\n" + canonical;
  }

  static String rewriteReservationLedger(String value) {
    return safe(value)
        .replaceAll(
            "(?i)decrement(?:s|ing)? `?on_hand`? and increment(?:s|ing)? `?reserved`? by"
                + " (?:the )?`?quantity`?",
            "leave `on_hand` unchanged and increment `reserved` by `quantity`")
        .replaceAll(
            "(?i)increment(?:s|ing)? `?on_hand`? and decrement(?:s|ing)? `?reserved`? by"
                + " (?:the )?line'?s quantity",
            "leave `on_hand` unchanged and decrement `reserved` by the line's quantity");
  }
}
