package com.navneet.executespec.auth.controller;

import com.navneet.executespec.auth.dto.*;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.auth.service.AuthService;
import com.navneet.executespec.auth.service.TierPermissionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final AppUserRepository userRepo;
    private final TierPermissionService tierPermissionService;
    private final ActivityAuditService auditService;
    private final com.navneet.executespec.repo.BitbucketRepoService bitbucketRepoService;

    private static final int REFRESH_COOKIE_MAX_AGE_SECONDS = 7 * 24 * 3600; // 7 days
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/register")
    public ResponseEntity<UserDto> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(req));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req,
                                               HttpServletResponse response) {
        LoginResponse loginResp = authService.login(req);
        String refreshToken = authService.generateRefreshToken(loginResp.user().id());
        setRefreshCookie(response, refreshToken);
        return ResponseEntity.ok(loginResp);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No refresh token");
        }
        LoginResponse loginResp = authService.refresh(refreshToken);
        // Rotate refresh token
        String newRefreshToken = authService.generateRefreshToken(loginResp.user().id());
        authService.logout(refreshToken);
        setRefreshCookie(response, newRefreshToken);
        return ResponseEntity.ok(loginResp);
    }

    @DeleteMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            HttpServletResponse response) {
        authService.logout(refreshToken);
        // Clear cookie
        Cookie cookie = new Cookie("refresh_token", "");
        cookie.setHttpOnly(true);
        cookie.setPath("/api/auth");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/verify-email")
    public ResponseEntity<Map<String, String>> verifyEmail(@RequestParam String token) {
        authService.verifyEmail(token);
        return ResponseEntity.ok(Map.of("message", "Email verified successfully"));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, String>> resendVerification(@RequestBody Map<String, String> body) {
        authService.resendVerification(body.get("email"));
        return ResponseEntity.ok(Map.of("message", "Verification email sent if address is registered"));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestBody Map<String, String> body) {
        authService.forgotPassword(body.get("email"));
        return ResponseEntity.ok(Map.of("message", "Password reset email sent if address is registered"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@RequestBody Map<String, String> body) {
        authService.resetPassword(body.get("token"), body.get("newPassword"));
        return ResponseEntity.ok(Map.of("message", "Password reset successfully"));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @Valid @RequestBody ChangePasswordRequest req) {
        authService.changePassword(userDetails.getUser().getId(), req);
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> me(@AuthenticationPrincipal AppUserDetails userDetails) {
        return ResponseEntity.ok(UserDto.from(userDetails.getUser(), userDetails.getOrgMember()));
    }

    /**
     * Invalidates all existing JWTs for the current user by setting {@code token_invalidated_at}
     * to now. Any token with {@code iat < token_invalidated_at} will be rejected by
     * {@code JwtAuthenticationFilter}. The caller must re-login to get a fresh token.
     */
    @PostMapping("/invalidate-sessions")
    public ResponseEntity<Map<String, String>> invalidateSessions(
            @AuthenticationPrincipal AppUserDetails userDetails) {
        String userId = userDetails.getUser().getId();
        userRepo.findById(userId).ifPresent(user -> {
            user.setTokenInvalidatedAt(java.time.Instant.now());
            userRepo.save(user);
            log.info("[Auth] Per-user session invalidation set for userId={}", userId);
        });
        return ResponseEntity.ok(Map.of("message", "All sessions invalidated. Please log in again."));
    }

    @GetMapping("/me/permissions")
    public ResponseEntity<TierPermissionService.TierPermissions> myPermissions(
            @AuthenticationPrincipal AppUserDetails userDetails) {
        return ResponseEntity.ok(tierPermissionService.getPermissions(userDetails.getUser().getId()));
    }

    // ── GitHub settings ───────────────────────────────────────────────────────

    record GitHubSettingsRequest(String githubPat, String githubOwner) {}

    /**
     * Saves the user's personal GitHub PAT and owner (username or org).
     * The PAT is stored as-is (not returned in subsequent /me calls — only hasGithubPat flag is exposed).
     * Pass an empty string to clear the PAT.
     */
    @PutMapping("/settings/github")
    public ResponseEntity<UserDto> updateGitHubSettings(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestBody GitHubSettingsRequest req) {
        AppUser user = userDetails.getUser();
        user.setGithubPat(blankToNull(req.githubPat()));
        user.setGithubOwner(blankToNull(req.githubOwner()));
        userRepo.save(user);
        log.info("User {} updated GitHub settings — owner={}, hasPat={}", user.getId(), user.getGithubOwner(), user.getGithubPat() != null);

        recordSettingsAudit(user, "USER_GITHUB_SETTINGS_CHANGED",
                "{\"hasToken\":" + (user.getGithubPat() != null) + ",\"owner\":\"" + user.getGithubOwner() + "\"}");

        return ResponseEntity.ok(UserDto.from(user, userDetails.getOrgMember()));
    }

    // ── Bitbucket settings ─────────────────────────────────────────────────────

    record BitbucketSettingsRequest(String bitbucketUsername, String bitbucketAppPassword,
                                    String bitbucketWorkspace, String bitbucketRepoSlug) {}

    /**
     * Saves the user's Bitbucket integration credentials.
     * Pass empty strings to clear individual fields.
     */
    @PutMapping("/settings/bitbucket")
    public ResponseEntity<UserDto> updateBitbucketSettings(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestBody BitbucketSettingsRequest req) {
        AppUser user = userDetails.getUser();
        user.setBitbucketUsername(blankToNull(req.bitbucketUsername()));
        user.setBitbucketAppPassword(blankToNull(req.bitbucketAppPassword()));
        user.setBitbucketWorkspace(blankToNull(req.bitbucketWorkspace()));
        user.setBitbucketRepoSlug(blankToNull(req.bitbucketRepoSlug()));
        userRepo.save(user);
        log.info("User {} updated Bitbucket settings — workspace={}, hasAppPassword={}",
                user.getId(), user.getBitbucketWorkspace(), user.getBitbucketAppPassword() != null);

        recordSettingsAudit(user, "USER_BITBUCKET_SETTINGS_CHANGED",
                "{\"hasAppPassword\":" + (user.getBitbucketAppPassword() != null)
                        + ",\"workspace\":\"" + user.getBitbucketWorkspace() + "\"}");

        return ResponseEntity.ok(UserDto.from(user, userDetails.getOrgMember()));
    }

    // ── AI provider key settings ─────────────────────────────────────────────

    record AiKeySettingsRequest(String openAiApiKey, String anthropicApiKey, String geminiApiKey) {}

    @PutMapping("/settings/ai-keys")
    public ResponseEntity<UserDto> updateAiKeySettings(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestBody AiKeySettingsRequest req) {
        // Tier gate: only PRO+ org admins / ENTERPRISE can manage AI keys
        var perms = tierPermissionService.getPermissions(userDetails.getUser().getId());
        if (!perms.canManageAiKeys()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "AI key management requires a PRO or ENTERPRISE plan with org admin access");
        }
        AppUser user = userDetails.getUser();
        user.setOpenAiApiKey(blankToNull(req.openAiApiKey()));
        user.setAnthropicApiKey(blankToNull(req.anthropicApiKey()));
        user.setGeminiApiKey(blankToNull(req.geminiApiKey()));
        userRepo.save(user);
        log.info("User {} updated AI key settings — openai={}, anthropic={}, gemini={}",
                user.getId(),
                user.getOpenAiApiKey() != null,
                user.getAnthropicApiKey() != null,
                user.getGeminiApiKey() != null);

        recordSettingsAudit(user, "USER_AI_KEYS_CHANGED",
                "{\"openai\":" + (user.getOpenAiApiKey() != null)
                        + ",\"anthropic\":" + (user.getAnthropicApiKey() != null)
                        + ",\"gemini\":" + (user.getGeminiApiKey() != null) + "}");

        return ResponseEntity.ok(UserDto.from(user, userDetails.getOrgMember()));
    }

    // ── JIRA settings ─────────────────────────────────────────────────────────

    record JiraSettingsRequest(String jiraBaseUrl, String jiraEmail, String jiraApiToken) {}

    @PutMapping("/settings/jira")
    public ResponseEntity<UserDto> updateJiraSettings(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestBody JiraSettingsRequest req) {
        AppUser user = userDetails.getUser();
        user.setJiraBaseUrl(blankToNull(req.jiraBaseUrl()));
        user.setJiraEmail(blankToNull(req.jiraEmail()));
        user.setJiraApiToken(blankToNull(req.jiraApiToken()));
        userRepo.save(user);
        log.info("User {} updated JIRA settings — url={}, hasToken={}", user.getId(), user.getJiraBaseUrl(), user.getJiraApiToken() != null);

        recordSettingsAudit(user, "USER_JIRA_SETTINGS_CHANGED",
                "{\"hasToken\":" + (user.getJiraApiToken() != null) + ",\"baseUrl\":\"" + user.getJiraBaseUrl() + "\"}");

        return ResponseEntity.ok(UserDto.from(user, userDetails.getOrgMember()));
    }

    // ── Confluence settings ───────────────────────────────────────────────────

    record ConfluenceSettingsRequest(String confluenceBaseUrl, String confluenceEmail, String confluenceApiToken) {}

    @PutMapping("/settings/confluence")
    public ResponseEntity<UserDto> updateConfluenceSettings(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestBody ConfluenceSettingsRequest req) {
        AppUser user = userDetails.getUser();
        user.setConfluenceBaseUrl(blankToNull(req.confluenceBaseUrl()));
        user.setConfluenceEmail(blankToNull(req.confluenceEmail()));
        user.setConfluenceApiToken(blankToNull(req.confluenceApiToken()));
        userRepo.save(user);
        log.info("User {} updated Confluence settings — url={}, hasToken={}", user.getId(), user.getConfluenceBaseUrl(), user.getConfluenceApiToken() != null);

        recordSettingsAudit(user, "USER_CONFLUENCE_SETTINGS_CHANGED",
                "{\"hasToken\":" + (user.getConfluenceApiToken() != null) + ",\"baseUrl\":\"" + user.getConfluenceBaseUrl() + "\"}");

        return ResponseEntity.ok(UserDto.from(user, userDetails.getOrgMember()));
    }

    // ── View mode settings ─────────────────────────────────────────────────────

    record ViewModeRequest(String viewMode) {}

    @PutMapping("/settings/view-mode")
    public ResponseEntity<UserDto> updateViewMode(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestBody ViewModeRequest req) {
        String mode = req.viewMode() != null ? req.viewMode().strip().toLowerCase() : "vibe";
        if (!"vibe".equals(mode) && !"pro".equals(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "viewMode must be 'vibe' or 'pro'");
        }
        AppUser user = userDetails.getUser();
        user.setViewMode(mode);
        userRepo.save(user);
        log.info("User {} updated view mode to {}", user.getId(), mode);
        return ResponseEntity.ok(UserDto.from(user, userDetails.getOrgMember()));
    }

    // ── Validation endpoints ──────────────────────────────────────────────────

    record ValidateGitHubRequest(String pat, String owner) {}
    record ValidateBitbucketRequest(String username, String appPassword, String workspace, String repoSlug) {}
    record ValidateJiraRequest(String baseUrl, String email, String apiToken) {}
    record ValidateConfluenceRequest(String baseUrl, String email, String apiToken) {}
    record ValidationResult(boolean valid, String message) {}

    @PostMapping("/settings/github/validate")
    public ResponseEntity<ValidationResult> validateGitHub(@RequestBody ValidateGitHubRequest req) {
        if (req.pat() == null || req.pat().isBlank()) {
            return ResponseEntity.ok(new ValidationResult(false, "PAT is required"));
        }
        return validateExternalConnection(
                "https://api.github.com/user",
                Map.of("Authorization", "Bearer " + req.pat().strip(),
                       "Accept", "application/vnd.github+json"),
                "GitHub PAT is valid",
                "GitHub returned %d — check your PAT");
    }

    @PostMapping("/settings/bitbucket/validate")
    public ResponseEntity<ValidationResult> validateBitbucket(@RequestBody ValidateBitbucketRequest req) {
        if (req.username() == null || req.username().isBlank()
                || req.appPassword() == null || req.appPassword().isBlank()) {
            return ResponseEntity.ok(new ValidationResult(false, "Username and app password are required"));
        }
        if (req.workspace() == null || req.workspace().isBlank()
                || req.repoSlug() == null || req.repoSlug().isBlank()) {
            return ResponseEntity.ok(new ValidationResult(false, "Workspace and repository slug are required"));
        }
        boolean valid = bitbucketRepoService.validateCredentials(
                req.workspace().strip(), req.repoSlug().strip(),
                req.username().strip(), req.appPassword().strip());
        if (valid) {
            return ResponseEntity.ok(new ValidationResult(true, "Bitbucket credentials are valid"));
        }
        return ResponseEntity.ok(new ValidationResult(false,
                "Could not access Bitbucket repository — check your credentials, workspace, and repo slug"));
    }

    @PostMapping("/settings/jira/validate")
    public ResponseEntity<ValidationResult> validateJira(@RequestBody ValidateJiraRequest req) {
        if (req.baseUrl() == null || req.email() == null || req.apiToken() == null) {
            return ResponseEntity.ok(new ValidationResult(false, "Base URL, email, and API token are all required"));
        }
        String auth = Base64.getEncoder().encodeToString((req.email().strip() + ":" + req.apiToken().strip()).getBytes());
        String url = req.baseUrl().strip().replaceAll("/+$", "") + "/rest/api/3/myself";
        return validateExternalConnection(url,
                Map.of("Authorization", "Basic " + auth,
                       "Accept", "application/json"),
                "JIRA credentials are valid",
                "JIRA returned %d — check your credentials");
    }

    @PostMapping("/settings/confluence/validate")
    public ResponseEntity<ValidationResult> validateConfluence(@RequestBody ValidateConfluenceRequest req) {
        if (req.baseUrl() == null || req.email() == null || req.apiToken() == null) {
            return ResponseEntity.ok(new ValidationResult(false, "Base URL, email, and API token are all required"));
        }
        String auth = Base64.getEncoder().encodeToString((req.email().strip() + ":" + req.apiToken().strip()).getBytes());
        String url = req.baseUrl().strip().replaceAll("/+$", "") + "/wiki/rest/api/space?limit=1";
        return validateExternalConnection(url,
                Map.of("Authorization", "Basic " + auth,
                       "Accept", "application/json"),
                "Confluence credentials are valid",
                "Confluence returned %d — check your credentials");
    }

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String blankToNull(String s) {
        return (s != null && !s.isBlank()) ? s.strip() : null;
    }

    private void recordSettingsAudit(AppUser user, String eventType, String newValueJson) {
        auditService.record(ActivityAudit.builder()
                .eventType(eventType)
                .entityType("USER").entityId(user.getId())
                .actorId(user.getId())
                .newValue(newValueJson)
                .build());
    }

    /**
     * Sends a GET request to an external service and returns a validation result.
     * Used by GitHub, JIRA, and Confluence validation endpoints.
     */
    private ResponseEntity<ValidationResult> validateExternalConnection(
            String url, Map<String, String> headers,
            String successMessage, String failureMessageTemplate) {
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET();
            headers.forEach(reqBuilder::header);
            HttpResponse<String> resp = HTTP_CLIENT.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return ResponseEntity.ok(new ValidationResult(true, successMessage));
            }
            return ResponseEntity.ok(new ValidationResult(false,
                    String.format(failureMessageTemplate, resp.statusCode())));
        } catch (Exception e) {
            return ResponseEntity.ok(new ValidationResult(false, "Connection failed: " + e.getMessage()));
        }
    }

    private void setRefreshCookie(HttpServletResponse response, String refreshToken) {
        Cookie cookie = new Cookie("refresh_token", refreshToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(false); // set true in production (HTTPS)
        cookie.setPath("/api/auth");
        cookie.setMaxAge(REFRESH_COOKIE_MAX_AGE_SECONDS);
        response.addCookie(cookie);
    }

    @GetMapping("generate")
    public String generate(@RequestParam(name = "password")String password) {
        return passwordEncoder.encode(password);
    }
}
