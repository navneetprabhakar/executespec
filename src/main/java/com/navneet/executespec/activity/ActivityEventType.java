package com.navneet.executespec.activity;

import java.util.EnumSet;
import java.util.Set;

/**
 * E1.8 — canonical taxonomy of every event the activity feed can carry.
 *
 * <p>Every value declares its {@link Tier} (drives admin-vs-user visibility), its {@link Surface}s
 * (where the event is written — activity feed, audit log, or both), and a {@code schemaVersion} for
 * the structured payload (so downstream consumers can evolve the payload shape without crashing on
 * old events).
 *
 * <p>Source plan §3.2 + §3.3 are the authority for what events exist; see {@code
 * docs/plans/pending-enterprise-readiness-execution-eval.md} §C for the UX-constraint mapping.
 *
 * <p><b>Adding a new event type:</b> declare it here with explicit tier/surfaces/schemaVersion. The
 * {@link #tier()} is the contract for RBAC filtering at the controller layer; {@link #surfaces()}
 * drives dual-write in {@link ActivityEventEmitter}. Don't repurpose existing values — bumping a
 * payload structure means bumping {@code schemaVersion} so consumers can branch.
 */
public enum ActivityEventType {

  // Tier 1 — high-signal, customer-visible (ACTIVITY surface)

  // Run lifecycle
  RUN_SUBMITTED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  RUN_PLAN_READY(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  RUN_PLAN_APPROVED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  RUN_COMPLETED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  RUN_FAILED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),

  // Launch lifecycle
  LAUNCH_SUBMITTED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  LAUNCH_ARCH_REVIEW(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  LAUNCH_PLAN_APPROVED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  LAUNCH_PAUSED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  LAUNCH_RESUMED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  LAUNCH_COMPLETED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),

  // PR lifecycle
  PR_OPENED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  PR_MERGED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  PR_CLOSED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),

  // Contract lifecycle (Phase E2 will start firing these)
  CONTRACT_DETECTED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  CONTRACT_VERSION_CHANGED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  CONTRACT_BREAKING_CHANGE(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  CONTRACT_DRIFT_DETECTED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  CONTRACT_DRIFT_DISMISSED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  CONTRACT_DRIFT_REFRESHED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  CONTRACT_CONSUMER_DECLARED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  CONTRACT_CONSUMER_REMOVED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),

  // Member changes — MEMBER_ROLE_CHANGED is dual-surface (also AUDIT) per plan §3.3
  MEMBER_ADDED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  MEMBER_REMOVED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  MEMBER_ROLE_CHANGED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY, Surface.AUDIT), 1),

  // Project setting (every change corresponds to a .executespec/ PR per plan §3.2)
  PROJECT_SETTING_CHANGED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),

  // Engineering standards (Phase E3)
  STANDARDS_VIOLATION_DETECTED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  STANDARDS_OVERRIDE_APPLIED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  STANDARDS_EXCEPTION_ADDED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  STANDARDS_EXCEPTION_REMOVED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),

  // ADR lifecycle (Phase E3)
  ADR_PROPOSED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  ADR_ACCEPTED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  ADR_DEPRECATED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  ADR_SUPERSEDED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),

  // .executespec/ folder lifecycle (E1.5 starts firing these)
  EXECUTESPEC_BOOTSTRAP_INITIATED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  EXECUTESPEC_BOOTSTRAP_COMPLETED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),
  EXECUTESPEC_BOOTSTRAP_FAILED(Tier.HIGH_SIGNAL, EnumSet.of(Surface.ACTIVITY), 1),

  // Tier 2 — admin-toggle visibility (ACTIVITY surface, admin-only filter)

  AUTO_DETECTION_RAN(Tier.ADMIN_TOGGLE, EnumSet.of(Surface.ACTIVITY), 1),
  ARCHITECTURE_DOC_REGENERATED(Tier.ADMIN_TOGGLE, EnumSet.of(Surface.ACTIVITY), 1),
  CODEMAP_REGENERATED(Tier.ADMIN_TOGGLE, EnumSet.of(Surface.ACTIVITY), 1),
  MEMORY_LEDGER_COMPACTED(Tier.ADMIN_TOGGLE, EnumSet.of(Surface.ACTIVITY), 1),
  AI_PROVIDER_CHANGED(Tier.ADMIN_TOGGLE, EnumSet.of(Surface.ACTIVITY), 1),
  QUOTA_APPROACHING_LIMIT(Tier.ADMIN_TOGGLE, EnumSet.of(Surface.ACTIVITY), 1),

  // Audit-only — AUDIT surface, gated by canViewAudit

  BILLING_CHANGE(Tier.AUDIT_ONLY, EnumSet.of(Surface.AUDIT), 1),
  PAYMENT_EVENT(Tier.AUDIT_ONLY, EnumSet.of(Surface.AUDIT), 1),
  QUOTA_ENFORCEMENT(Tier.AUDIT_ONLY, EnumSet.of(Surface.AUDIT), 1),
  SENSITIVE_ADMIN_ACTION(Tier.AUDIT_ONLY, EnumSet.of(Surface.AUDIT), 1);

  private final Tier tier;
  private final Set<Surface> surfaces;
  private final int schemaVersion;

  ActivityEventType(Tier tier, EnumSet<Surface> surfaces, int schemaVersion) {
    this.tier = tier;
    // EnumSet preserves declaration order; immutable copy to keep the
    // contract clean (callers can't mutate a shared set).
    this.surfaces = Set.copyOf(surfaces);
    this.schemaVersion = schemaVersion;
  }

  public Tier tier() {
    return tier;
  }

  public Set<Surface> surfaces() {
    return surfaces;
  }

  public int schemaVersion() {
    return schemaVersion;
  }

  public boolean writesTo(Surface s) {
    return surfaces.contains(s);
  }

  /**
   * Visibility tier for an event type. Drives RBAC filtering at the activity-feed query layer (see
   * Phase E4).
   */
  public enum Tier {
    /** Always shown to project members in the Activity tab. */
    HIGH_SIGNAL,
    /**
     * Shown only when an admin (ORG_ADMIN / PROJECT_ADMIN) toggles the "show admin events" filter.
     */
    ADMIN_TOGGLE,
    /** Never appears in the Activity feed; goes to the Audit tab only. */
    AUDIT_ONLY
  }

  /**
   * Where a given event lands. {@link ActivityEventEmitter} dual-writes when an event declares
   * multiple surfaces (e.g. MEMBER_ROLE_CHANGED lands in both ACTIVITY and AUDIT).
   */
  public enum Surface {
    /** activity_feed table — customer-facing project activity tab. */
    ACTIVITY,
    /** activity_audit table — compliance-relevant immutable log. */
    AUDIT
  }
}
