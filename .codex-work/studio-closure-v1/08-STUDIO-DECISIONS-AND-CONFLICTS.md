# ExecuteSpec Studio — Decisions and Conflict Register

Date: 2026-07-23  
Status: **Phase 0 decisions closed**

## Tier resolution

| Studio label | Price | Live entitlement mapping | Decision |
|---|---:|---|---|
| Free | $0/month | backend `FREE` | unchanged |
| Pro | $20/month | backend `PRO` | unchanged |
| Max | $100/month | backend `PRO_PLUS` | Studio renames the customer-facing label only; retain `PRO_PLUS` internally until a deliberate migration |
| Use your own keys | $50/month current route | backend `BYOK` | key-source/billing option reached from Account → AI providers; not a fourth primary Studio marketing card |
| Team | $50/seat/month | backend `TEAM` | graduation destination, not Studio tier |
| Enterprise | custom | backend `ENTERPRISE` | separate product surface |

Max is not BYOK. Max is the managed high-capacity individual tier currently represented by Pro+. Existing BYOK customers receive the professional Studio shell but retain their actual billing and entitlement semantics; the UI must never imply they purchased Max capacity.

## Source conflicts closed

1. Old documents naming six top-level modes are superseded by three durable surfaces: Home, Project, Account.
2. Create/Canvas/Versions are compositions inside Project; Build/Data/Checks/Ship are the stable lenses.
3. Professional Mode is progressive disclosure in the same Project shell.
4. The old 20-domain settings catalog is an implementation inventory, not visible navigation.
5. “Max” in historic migrations is not reused as a backend enum; Studio Max maps to current Pro+.
6. Repositories, branches, providers, and evidence are hidden by default for casual users but never removed.
7. Upgrade gates appear at intent, preserve the current Task, and explain the exact capability/limit.
8. Third-party names stay visible; logos remain deferred by explicit product decision.
9. Mobile is a decision/review/recovery surface; full source editing is desktop/local-hands work.
10. A feature listed in Settings does not count as UX coverage without an entry point, state, effect, and recovery.

## Residual implementation decisions, not brainstorm blockers

- Whether to migrate the `PRO_PLUS` enum/string to `MAX` is an engineering migration decision.
- Whether BYOK remains a standalone subscription or becomes a Max add-on is a pricing/commerce decision; designs preserve both honest paths.
- Custom domains may ship after first Studio release if current platform proof is insufficient.

