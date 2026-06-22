package com.navneet.executespec.entitlements;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.router.ModelRegistry;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Read side of T26 — assembles {@link GovernancePolicyResponse} from the live enforcement sources. No
 * new state: it reuses {@link EntitlementResolver#resolveTier} (so the read can never disagree with
 * the tier the guards see), {@link ModelRegistry} for the free-tier lock, and the org's M-032/M-039
 * columns for the cost-approval threshold and GitHub-owner allowlist.
 */
@Service
@RequiredArgsConstructor
public class GovernancePolicyResolver {

  private static final String TOOL_POLICY_NOTE =
      "All tools available subject to approval + permission rules; no per-tool gating enforced yet.";

  private final AppUserRepository userRepo;
  private final OrgRepository orgRepo;
  private final EntitlementResolver entitlementResolver;
  private final ModelRegistry modelRegistry;

  /** Resolve the effective governance policy for the given user. */
  public GovernancePolicyResponse resolve(String userId) {
    AppUser user =
        userRepo
            .findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));
    String tier = entitlementResolver.resolveTier(user);

    GovernancePolicyResponse.ModelPolicy modelPolicy = modelPolicyFor(tier);

    Double costApprovalThresholdUsd = null;
    List<String> allowedGithubOwners = List.of();
    if (user.getOrgId() != null) {
      Org org = orgRepo.findById(user.getOrgId()).orElse(null);
      if (org != null) {
        BigDecimal threshold = org.getRequireAdminApprovalAboveUsd();
        costApprovalThresholdUsd = threshold == null ? null : threshold.doubleValue();
        allowedGithubOwners = parseOwners(org.getAllowedGithubOwners());
      }
    }

    boolean superAdminBypass = "SUPER_ADMIN".equals(user.getGlobalRole());

    return new GovernancePolicyResponse(
        tier,
        modelPolicy,
        new GovernancePolicyResponse.ToolPolicy(false, TOOL_POLICY_NOTE),
        costApprovalThresholdUsd,
        allowedGithubOwners,
        superAdminBypass);
  }

  private GovernancePolicyResponse.ModelPolicy modelPolicyFor(String tier) {
    boolean freeLocked = "FREE".equalsIgnoreCase(tier);
    if (!freeLocked) {
      return new GovernancePolicyResponse.ModelPolicy(
          false, null, List.of(), "All benchmarked models available on " + tier + ".");
    }
    String provider = modelRegistry.freeTierProvider();
    List<String> freeModels = modelRegistry.freeTierModels();
    String providerSuffix =
        (provider == null || provider.isBlank()) ? "" : " (provider: " + provider + ")";
    return new GovernancePolicyResponse.ModelPolicy(
        true,
        provider,
        freeModels == null ? List.of() : freeModels,
        "FREE tier is locked to free-tier models" + providerSuffix + ".");
  }

  /** Split the M-039 allowlist CSV into trimmed, non-empty owners. Empty/blank = allow-all = []. */
  static List<String> parseOwners(String csv) {
    if (csv == null || csv.isBlank()) return List.of();
    List<String> owners = new ArrayList<>();
    for (String s : csv.split(",")) {
      String t = s.trim();
      if (!t.isEmpty()) owners.add(t);
    }
    return owners;
  }
}
