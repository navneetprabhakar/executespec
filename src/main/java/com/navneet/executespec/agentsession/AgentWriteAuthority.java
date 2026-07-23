package com.navneet.executespec.agentsession;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;

/**
 * Resolves whether the acting user may submit runs against a project, without throwing.
 *
 * <p>Routing needs this as a fact, not as a control-flow exception: a read-only collaborator must
 * still get an answer, just not a confirmable run card. The canonical decision stays in {@link
 * ProjectAccessGuard#assertCanWrite}; this only re-expresses its outcome as a value so a proposal
 * can be built honestly. It is never a substitute for the guard call the submission gate makes.
 */
final class AgentWriteAuthority {

  private AgentWriteAuthority() {}

  /**
   * @return {@code TRUE} when the caller may write the project, {@code FALSE} when the guard
   *     refuses, {@code null} when authority could not be evaluated at all
   */
  static Boolean resolve(ProjectAccessGuard guard, String projectId, AppUserDetails user) {
    if (guard == null || projectId == null || projectId.isBlank() || user == null) return null;
    try {
      guard.assertCanWrite(projectId, user);
      return Boolean.TRUE;
    } catch (org.springframework.web.server.ResponseStatusException denied) {
      return Boolean.FALSE;
    } catch (SecurityException denied) {
      return Boolean.FALSE;
    } catch (RuntimeException unavailable) {
      // An infrastructure failure is not evidence of missing authority; leave it unevaluated so the
      // submission gate still decides rather than silently downgrading a legitimate proposal.
      return null;
    }
  }
}
