package com.navneet.executespec.agentsession.subagent;

import java.nio.file.Files;
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
 */
@Service
@RequiredArgsConstructor
public class ImplementerMutationService {

  public static final String FLAG = "agent.subagents.implementer.enabled";

  private final SubagentProtocolService protocolService;
  private final AgentSessionRepository sessionRepository;
  private final ToolRegistry toolRegistry;
  private final ObjectMapper objectMapper;
  private final FeatureFlagService featureFlagService;

  public WriterReservation reserve(
      String parentSessionId, String requestId, String scope, AppUserDetails user, String orgId) {
    requireUser(user);
    if (!featureFlagService.isEnabled(FLAG)) {
      throw new IllegalStateException("IMPLEMENTER is disabled");
    }
    if (!isLocalHandsSession(parentSessionId)) {
      throw new SecurityException("writer reservations require a CLI or VS Code session");
    }
    Path root = normalizeScope(scope);
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
    AgentSession parent =
        sessionRepository
            .findById(parentSessionId)
            .orElseThrow(() -> new IllegalArgumentException("Agent session not found"));
    if (!user.getUser().getId().equals(parent.getUserId())
        || (orgId != null && !orgId.equals(parent.getOrgId()))) {
      throw new SecurityException("agent session ownership mismatch");
    }
    // Browser sessions must never gain local mutation authority. CLI and VS Code are the
    // local-hands clients and still require a PAT at the controller/security boundary.
    if (!"CLI".equalsIgnoreCase(parent.getClientSurface())
        && !"VSCODE".equalsIgnoreCase(parent.getClientSurface())) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, "IMPLEMENTER is not available to WEB");
    }
    Path root;
    try {
      root = normalizeScope(scope);
    } catch (IllegalArgumentException e) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, e.getMessage());
    }
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

  private boolean isLocalHandsSession(String sessionId) {
    AgentSession session = sessionRepository.findById(sessionId).orElse(null);
    return session != null
        && ("CLI".equalsIgnoreCase(session.getClientSurface())
            || "VSCODE".equalsIgnoreCase(session.getClientSurface()));
  }

  private static Path normalizeScope(String scope) {
    if (scope == null || scope.isBlank())
      throw new IllegalArgumentException("writer scope is required");
    Path root = Path.of(scope).toAbsolutePath().normalize();
    if (!root.isAbsolute() || !Files.isDirectory(root)) {
      throw new IllegalArgumentException("writer scope must be an existing directory");
    }
    return root;
  }

  private static void requireUser(AppUserDetails user) {
    if (user == null || user.getUser() == null)
      throw new SecurityException("authenticated user is required");
  }
}
