package com.navneet.executespec.agentsession.subagent;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.WriterReservation;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.Tool;
import com.navneet.executespec.tool.ToolRegistry;
import com.navneet.executespec.tool.ToolResult;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Single-writer mutation seam for the future IMPLEMENTER role.
 *
 * <p>This service intentionally executes only {@code writeFile} and {@code editFile}, and only for
 * CLI/VS Code sessions with an active, exact-scope writer reservation. It is not a model loop and
 * is disabled by default; callers must opt in through the feature flag and explicitly release the
 * reservation when the edit sequence ends.
 *
 * <p><strong>Execution locality.</strong> Despite the CLI/VS Code (local-hands) gate, these
 * mutations run on the ExecuteSpec server. The target directory is therefore derived from server
 * state by {@link ImplementerWorkspaceScope}; a caller-supplied absolute host path is refused, not
 * honoured.
 */
@Service
@RequiredArgsConstructor
public class ImplementerMutationService {

  public static final String FLAG = "agent.subagents.implementer.enabled";

  static final String WEB_SURFACE_REJECTION = "IMPLEMENTER is not available to WEB";

  private final SubagentProtocolService protocolService;
  private final AgentSessionRepository sessionRepository;
  private final ToolRegistry toolRegistry;
  private final ObjectMapper objectMapper;
  private final FeatureFlagService featureFlagService;
  private final ImplementerWorkspaceScope workspaceScope;

  public WriterReservation reserve(
      String parentSessionId, String requestId, String scope, AppUserDetails user, String orgId) {
    requireUser(user);
    if (!featureFlagService.isEnabled(FLAG)) {
      throw new IllegalStateException("IMPLEMENTER is disabled");
    }
    AgentSession session = requireOwnedLocalHandsSession(parentSessionId, user, orgId);
    Path root = workspaceScope.resolve(session, scope);
    return protocolService.reserveWriter(
        parentSessionId, requestId, root.toString(), user.getUser().getId(), orgId);
  }

  public WriterReservation release(
      String parentSessionId,
      String reservationId,
      String reason,
      AppUserDetails user,
      String orgId) {
    requireUser(user);
    return protocolService.releaseWriter(
        parentSessionId, reservationId, reason, user.getUser().getId(), orgId);
  }

  public ToolResult mutate(
      String parentSessionId,
      String reservationId,
      String scope,
      String toolName,
      JsonNode args,
      AppUserDetails user,
      String orgId) {
    if (!featureFlagService.isEnabled(FLAG)) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, "IMPLEMENTER is disabled");
    }
    requireUser(user);
    AgentSession parent;
    try {
      parent = requireOwnedLocalHandsSession(parentSessionId, user, orgId);
    } catch (SecurityException e) {
      if (WEB_SURFACE_REJECTION.equals(e.getMessage())) {
        return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, WEB_SURFACE_REJECTION);
      }
      throw e;
    }
    Path root;
    try {
      root = workspaceScope.resolve(parent, scope);
    } catch (IllegalArgumentException | SecurityException e) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, e.getMessage());
    }
    return mutateResolved(parentSessionId, reservationId, root, toolName, args, user, orgId);
  }

  /**
   * Mutation against an already server-resolved root. Used by {@link ImplementerExecutionService},
   * whose model loop is bound to the same root this run resolved once; re-resolving an absolute
   * path per call would be rejected by design.
   */
  ToolResult mutateResolved(
      String parentSessionId,
      String reservationId,
      Path root,
      String toolName,
      JsonNode args,
      AppUserDetails user,
      String orgId) {
    if (!featureFlagService.isEnabled(FLAG)) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, "IMPLEMENTER is disabled");
    }
    requireUser(user);
    if (!"writeFile".equals(toolName) && !"editFile".equals(toolName)) {
      return ToolResult.err(
          ToolResult.ErrorKind.FORBIDDEN, "IMPLEMENTER allows file mutations only");
    }
    protocolService.authorizeWriter(
        parentSessionId, reservationId, root.toString(), user.getUser().getId(), orgId);
    Tool tool = toolRegistry.find(toolName);
    if (tool == null)
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "unknown mutation tool");
    return tool.execute(
        args == null ? objectMapper.createObjectNode() : args,
        new ExecutionContext(parentSessionId, "IMPLEMENTER", root));
  }

  /**
   * Load the session and prove the caller owns it and is on a local-hands surface. Reservation used
   * to check only the surface, so any authenticated user could reserve (and thereby block) the
   * writer on another tenant's session.
   */
  AgentSession requireOwnedLocalHandsSession(String sessionId, AppUserDetails user, String orgId) {
    AgentSession session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Agent session not found"));
    if (!user.getUser().getId().equals(session.getUserId())
        || (orgId != null && !orgId.equals(session.getOrgId()))) {
      throw new SecurityException("agent session ownership mismatch");
    }
    // Browser sessions must never gain mutation authority. CLI and VS Code are the local-hands
    // clients and still require a PAT at the controller/security boundary.
    if (!"CLI".equalsIgnoreCase(session.getClientSurface())
        && !"VSCODE".equalsIgnoreCase(session.getClientSurface())) {
      throw new SecurityException(WEB_SURFACE_REJECTION);
    }
    return session;
  }

  private static void requireUser(AppUserDetails user) {
    if (user == null || user.getUser() == null)
      throw new SecurityException("authenticated user is required");
  }
}
