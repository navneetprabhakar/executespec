package com.navneet.agentteam.auth.controller;

import com.navneet.agentteam.auth.dto.*;
import com.navneet.agentteam.auth.entity.AppUser;
import com.navneet.agentteam.auth.repository.AppUserRepository;
import com.navneet.agentteam.auth.security.AppUserDetails;
import com.navneet.agentteam.auth.service.AuthService;
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

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final AppUserRepository userRepo;

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
        String newPat   = (req.githubPat()   != null) ? req.githubPat().strip()   : null;
        String newOwner = (req.githubOwner() != null) ? req.githubOwner().strip() : null;
        // Treat empty strings as null (clear)
        user.setGithubPat(   (newPat   != null && !newPat.isBlank())   ? newPat   : null);
        user.setGithubOwner((newOwner != null && !newOwner.isBlank()) ? newOwner : null);
        userRepo.save(user);
        log.info("User {} updated GitHub settings — owner={}, hasPat={}", user.getId(), user.getGithubOwner(), user.getGithubPat() != null);
        return ResponseEntity.ok(UserDto.from(user, userDetails.getOrgMember()));
    }

    // ── AI provider key settings ─────────────────────────────────────────────

    record AiKeySettingsRequest(String openAiApiKey, String anthropicApiKey, String geminiApiKey) {}

    @PutMapping("/settings/ai-keys")
    public ResponseEntity<UserDto> updateAiKeySettings(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestBody AiKeySettingsRequest req) {
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
        return ResponseEntity.ok(UserDto.from(user, userDetails.getOrgMember()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String blankToNull(String s) {
        return (s != null && !s.isBlank()) ? s.strip() : null;
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
