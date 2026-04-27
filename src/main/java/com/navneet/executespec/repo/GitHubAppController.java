package com.navneet.executespec.repo;

import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;

import java.time.Instant;
import java.util.Map;

/**
 * Phase 1.2 #18 — GitHub App install + callback (skeleton).
 *
 * <p><b>Skeleton scope:</b>
 * <ul>
 *   <li>{@code GET /api/auth/github-app/install-url} — returns the GitHub App
 *       install URL the org-admin should send their user to. Front-end
 *       turns this into a button on the IntegrationsPage.</li>
 *   <li>{@code POST /api/auth/github-app/callback} — call site for the GitHub
 *       App webhook ({@code installation.created}); persists
 *       {@code github_installation_id} + {@code github_installed_at} on
 *       the org row (V127 columns).</li>
 *   <li>{@code GET /api/auth/github-app/status} — front-end probe so the
 *       Settings → Integrations panel can show "GitHub App ✓ installed".</li>
 *   <li>{@code DELETE /api/auth/github-app/install} — admin-triggered
 *       uninstall (clears the columns; the user still has to revoke from
 *       GitHub side).</li>
 * </ul>
 *
 * <p><b>NOT in this skeleton:</b> webhook signature verification (HMAC),
 * App-token minting (JWT signing with the App's private key, exchange for
 * an installation access token), repo permission checks. These all need
 * the GitHub App credentials wired into env config + a real install. The
 * skeleton lets the data model + admin UX + status reporting exist
 * independently so Phase 2 can wire the real flow without rebuilding the
 * surface.
 */
@RestController
@RequestMapping("/api/auth/github-app")
@RequiredArgsConstructor
@Slf4j
public class GitHubAppController {

    private final OrgRepository orgRepo;

    @Value("${github.app.name:executespec-app}")
    private String appName;

    /** Public install URL. GitHub redirects back with `installation_id` query param. */
    @GetMapping("/install-url")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
    public Map<String, String> installUrl(@AuthenticationPrincipal AppUserDetails principal) {
        String orgId = requireOrgId(principal);
        // GitHub install URL format: https://github.com/apps/<app-name>/installations/new?state=<orgId>
        String url = "https://github.com/apps/" + appName + "/installations/new?state="
            + java.net.URLEncoder.encode(orgId, java.nio.charset.StandardCharsets.UTF_8);
        return Map.of("installUrl", url, "appName", appName);
    }

    /** Status probe — `{installed: true, installationId: "...", installedAt: "..."}`. */
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','DEVELOPER','PROJECT_ADMIN')")
    public Map<String, Object> status(@AuthenticationPrincipal AppUserDetails principal) {
        Org org = requireOrg(principal);
        boolean installed = org.getGithubInstallationId() != null;
        return Map.of(
            "installed", installed,
            "installationId", installed ? String.valueOf(org.getGithubInstallationId()) : "",
            "installedAt", org.getGithubInstalledAt() != null ? org.getGithubInstalledAt().toString() : ""
        );
    }

    /**
     * Persist the install. Called from a GitHub App webhook in Phase 2; for
     * now the front-end can POST it manually after the install bounce
     * lands the user back on the IntegrationsPage with `?installation_id=`.
     *
     * <p>Webhook signature verification is NOT implemented yet — this
     * endpoint requires ORG_ADMIN auth in the meantime so it can't be
     * abused via the public webhook URL.
     */
    @PostMapping("/callback")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
    public Map<String, Object> callback(@AuthenticationPrincipal AppUserDetails principal,
                                        @RequestBody InstallCallback body) {
        if (body == null || body.installationId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "installationId required");
        }
        Org org = requireOrg(principal);
        org.setGithubInstallationId(body.installationId());
        org.setGithubInstalledAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        orgRepo.save(org);
        log.info("[GitHubApp] installed installationId={} on orgId={} by userId={}",
            body.installationId(), org.getId(), principal.getUser().getId());
        return Map.of("ok", true, "installationId", body.installationId());
    }

    @DeleteMapping("/install")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
    public ResponseEntity<Void> uninstall(@AuthenticationPrincipal AppUserDetails principal) {
        Org org = requireOrg(principal);
        org.setGithubInstallationId(null);
        org.setGithubInstalledAt(null);
        org.setUpdatedAt(Instant.now());
        orgRepo.save(org);
        log.info("[GitHubApp] uninstalled from orgId={} by userId={}",
            org.getId(), principal.getUser().getId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Browser-bounce convenience — same as `/install-url` but returns a 302
     * directly so a `<a href>` can fire the install in one click without
     * a JavaScript fetch. Useful from emails / docs.
     */
    @GetMapping("/install")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
    public RedirectView installRedirect(@AuthenticationPrincipal AppUserDetails principal) {
        String orgId = requireOrgId(principal);
        return new RedirectView("https://github.com/apps/" + appName
            + "/installations/new?state="
            + java.net.URLEncoder.encode(orgId, java.nio.charset.StandardCharsets.UTF_8));
    }

    private Org requireOrg(AppUserDetails principal) {
        return orgRepo.findById(requireOrgId(principal))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
    }

    private String requireOrgId(AppUserDetails principal) {
        if (principal == null || principal.getUser() == null || principal.getUser().getOrgId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User has no org");
        }
        return principal.getUser().getOrgId();
    }

    public record InstallCallback(Long installationId) {}
}
