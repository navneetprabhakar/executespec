package com.navneet.executespec.auth.service;

import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * RR-008 (2026-07-21, P1 concurrency/data integrity) — transactional, provider-scoped credential
 * updates.
 *
 * <p>The {@code AuthController} settings endpoints previously mutated the {@link AppUser} carried
 * in the authentication principal — a detached snapshot captured at token-resolution time — and
 * called {@code userRepo.save}. {@code AppUser} has no {@code @Version}, so that save wrote back
 * EVERY column of the row as it looked at login. Two concurrent settings changes (or a settings
 * change racing any other write to the row) therefore silently overwrote each other's unrelated
 * credential/profile fields with stale values.
 *
 * <p>Each method here refetches the user under a pessimistic row lock ({@link
 * AppUserRepository#findByIdForUpdate}) inside a single transaction, writes ONLY the fields for
 * that provider from current DB state, and appends the audit event in the SAME transaction. The row
 * lock serializes concurrent credential saves for one user, so a provider-scoped update can no
 * longer revert another provider's fields, and the domain mutation and its audit row commit
 * atomically.
 *
 * <p>External credential validation (the GitHub PAT probe, tier gates) stays in the controller so
 * no blocking network call is made while holding the row lock. Audit JSON shapes are preserved
 * verbatim from the prior controller code; restructuring that serialization is a separate finding
 * (RR-009).
 */
@Service
@RequiredArgsConstructor
public class UserSettingsUpdateService {

  private final AppUserRepository userRepo;
  private final ActivityAuditService auditService;

  /** Result of a credential clear: the fresh user plus whether a value was actually present. */
  public record RevokeResult(AppUser user, boolean existed) {}

  @Transactional
  public AppUser updateGitHub(String userId, String githubPat, String githubOwner) {
    AppUser user = lock(userId);
    user.setGithubPat(githubPat);
    user.setGithubOwner(githubOwner);
    userRepo.save(user);
    audit(
        user,
        "USER_GITHUB_SETTINGS_CHANGED",
        "{\"hasToken\":"
            + (user.getGithubPat() != null)
            + ",\"owner\":\""
            + user.getGithubOwner()
            + "\"}");
    return user;
  }

  @Transactional
  public AppUser updateBitbucket(
      String userId, String username, String appPassword, String workspace, String repoSlug) {
    AppUser user = lock(userId);
    user.setBitbucketUsername(username);
    user.setBitbucketAppPassword(appPassword);
    user.setBitbucketWorkspace(workspace);
    user.setBitbucketRepoSlug(repoSlug);
    userRepo.save(user);
    audit(
        user,
        "USER_BITBUCKET_SETTINGS_CHANGED",
        "{\"hasAppPassword\":"
            + (user.getBitbucketAppPassword() != null)
            + ",\"workspace\":\""
            + user.getBitbucketWorkspace()
            + "\"}");
    return user;
  }

  /**
   * Patch semantics: only non-blank values are written, so the Web settings surface saving one
   * provider at a time never erases the others. Destructive removal is {@link #revokeAiKey}.
   */
  @Transactional
  public AppUser updateAiKeys(
      String userId,
      String openAiApiKey,
      String anthropicApiKey,
      String geminiApiKey,
      String deepSeekApiKey,
      String fireworksApiKey) {
    AppUser user = lock(userId);
    if (blankToNull(openAiApiKey) != null) {
      user.setOpenAiApiKey(openAiApiKey.strip());
    }
    if (blankToNull(anthropicApiKey) != null) {
      user.setAnthropicApiKey(anthropicApiKey.strip());
    }
    if (blankToNull(geminiApiKey) != null) {
      user.setGeminiApiKey(geminiApiKey.strip());
    }
    if (blankToNull(deepSeekApiKey) != null) {
      user.setDeepSeekApiKey(deepSeekApiKey.strip());
    }
    if (blankToNull(fireworksApiKey) != null) {
      user.setFireworksApiKey(fireworksApiKey.strip());
    }
    userRepo.save(user);
    audit(
        user,
        "USER_AI_KEYS_CHANGED",
        "{\"openai\":"
            + (user.getOpenAiApiKey() != null)
            + ",\"anthropic\":"
            + (user.getAnthropicApiKey() != null)
            + ",\"gemini\":"
            + (user.getGeminiApiKey() != null)
            + ",\"deepseek\":"
            + (user.getDeepSeekApiKey() != null)
            + ",\"fireworks\":"
            + (user.getFireworksApiKey() != null)
            + "}");
    return user;
  }

  @Transactional
  public RevokeResult revokeAiKey(String userId, String normalizedProvider) {
    AppUser user = lock(userId);
    boolean existed =
        switch (normalizedProvider) {
          case "openai" -> clearPresent(user.getOpenAiApiKey(), () -> user.setOpenAiApiKey(null));
          case "anthropic" ->
              clearPresent(user.getAnthropicApiKey(), () -> user.setAnthropicApiKey(null));
          case "gemini" -> clearPresent(user.getGeminiApiKey(), () -> user.setGeminiApiKey(null));
          case "deepseek" ->
              clearPresent(user.getDeepSeekApiKey(), () -> user.setDeepSeekApiKey(null));
          case "fireworks" ->
              clearPresent(user.getFireworksApiKey(), () -> user.setFireworksApiKey(null));
          default ->
              throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported provider");
        };
    userRepo.save(user);
    audit(
        user,
        "USER_AI_KEY_REVOKED",
        "{\"provider\":\"" + normalizedProvider + "\",\"previouslyPresent\":" + existed + "}");
    return new RevokeResult(user, existed);
  }

  @Transactional
  public AppUser updateJira(String userId, String baseUrl, String email, String apiToken) {
    AppUser user = lock(userId);
    user.setJiraBaseUrl(baseUrl);
    user.setJiraEmail(email);
    user.setJiraApiToken(apiToken);
    userRepo.save(user);
    audit(
        user,
        "USER_JIRA_SETTINGS_CHANGED",
        "{\"hasToken\":"
            + (user.getJiraApiToken() != null)
            + ",\"baseUrl\":\""
            + user.getJiraBaseUrl()
            + "\"}");
    return user;
  }

  @Transactional
  public AppUser updateConfluence(String userId, String baseUrl, String email, String apiToken) {
    AppUser user = lock(userId);
    user.setConfluenceBaseUrl(baseUrl);
    user.setConfluenceEmail(email);
    user.setConfluenceApiToken(apiToken);
    userRepo.save(user);
    audit(
        user,
        "USER_CONFLUENCE_SETTINGS_CHANGED",
        "{\"hasToken\":"
            + (user.getConfluenceApiToken() != null)
            + ",\"baseUrl\":\""
            + user.getConfluenceBaseUrl()
            + "\"}");
    return user;
  }

  private AppUser lock(String userId) {
    return userRepo
        .findByIdForUpdate(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
  }

  private void audit(AppUser user, String eventType, String newValueJson) {
    auditService.record(
        ActivityAudit.builder()
            .eventType(eventType)
            .entityType("USER")
            .entityId(user.getId())
            .actorId(user.getId())
            .newValue(newValueJson)
            .build());
  }

  private static boolean clearPresent(String current, Runnable clear) {
    boolean present = current != null && !current.isBlank();
    clear.run();
    return present;
  }

  private static String blankToNull(String s) {
    return (s != null && !s.isBlank()) ? s.strip() : null;
  }
}
