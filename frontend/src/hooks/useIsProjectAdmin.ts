import {useAuthStore, selectIsOrgAdmin} from '@/stores/useAuthStore';

/**
 * Whether the current user has admin authority over a project — use this hook
 * to gate destructive or admin-only project actions in the UI (Launch, Add
 * Repo, Generate Architecture, Reset Workspace, Delete Project, Update
 * Settings, etc.).
 *
 * P-25 (UX audit, 2026-05-03): pre-fix, every Project surface assumed the
 * caller was an admin. DEVELOPER + VIEWER roles saw the same Launch / Add
 * Repo / Generate buttons as ORG_ADMIN, clicked them, and got 403 from the
 * backend. The audit's specific complaint: "Enterprise org admins onboarding
 * their team report 'my devs say it's broken' because the buttons appear
 * to work and then 403."
 *
 * Today's implementation: returns true iff the user is ORG_ADMIN or
 * SUPER_ADMIN at the org level. This matches the backend's current
 * authorisation model (per-project member roles aren't yet plumbed through
 * the dashboard endpoint — when they are, this hook is the single place to
 * extend so every gated CTA picks up the new logic for free).
 *
 * Future extension: when {@code project.members[me].role} (PROJECT_ADMIN /
 * PROJECT_DEVELOPER) is exposed via the project DTO, OR-merge it in here
 * so a DEVELOPER promoted to PROJECT_ADMIN on a specific project can use
 * its admin buttons. Until then we err on the side of safety — non-org-
 * admins see disabled CTAs with explanatory tooltips.
 */
export function useIsProjectAdmin(): boolean {
  return useAuthStore(selectIsOrgAdmin);
}
