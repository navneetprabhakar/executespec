package com.navneet.executespec.security.dependency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.security.guardrail.GuardrailLevel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RR-247 / RR-248 — the two supply-chain claims this policy makes about dependency installs.
 *
 * <p>Both were previously untrue in a way that read as coverage: Yarn's {@code --immutable} was
 * accepted as proof that lifecycle scripts were disabled (it is a lockfile flag), and registry
 * allowlisting compared URLs with {@code startsWith} (a prefix test, not an origin test).
 */
class DependencySecurityPolicyTest {

  private DependencySecurityPolicy policy;

  @BeforeEach
  void setUp() {
    policy = new DependencySecurityPolicy(null);
    ReflectionTestUtils.setField(policy, "requireIgnoreScripts", true);
    ReflectionTestUtils.setField(policy, "requireLockfile", true);
    ReflectionTestUtils.setField(policy, "requireAudit", true);
    ReflectionTestUtils.setField(policy, "allowPrerelease", false);
    ReflectionTestUtils.setField(
        policy,
        "allowedRegistriesCsv",
        "https://registry.npmjs.org,https://registry.bun.sh,https://registry.pnpm.io");
  }

  private boolean permitted(String segment) {
    return policy.validateSegment(segment, segment, GuardrailLevel.MEDIUM).permitted();
  }

  private boolean permittedHigh(String segment) {
    return policy.validateSegment(segment, segment, GuardrailLevel.HIGH).permitted();
  }

  // ---------------------------------------------------------------- RR-247

  @Test
  void yarnImmutableAloneNoLongerCountsAsScriptSuppression() {
    // --immutable pins the lockfile; every postinstall in the tree still runs.
    assertThat(permitted("yarn install --immutable")).isFalse();
  }

  @Test
  void yarnClassicIgnoreScriptsIsAccepted() {
    assertThat(permitted("yarn install --ignore-scripts --immutable")).isTrue();
  }

  @Test
  void yarnBerrySkipBuildModeIsAccepted() {
    assertThat(permitted("yarn install --immutable --mode=skip-build")).isTrue();
  }

  @Test
  void yarnBerryEnableScriptsFalseEnvIsAccepted() {
    assertThat(permitted("YARN_ENABLE_SCRIPTS=false yarn install --immutable")).isTrue();
  }

  @Test
  void yarnEnableScriptsTrueEnvIsNotSuppression() {
    assertThat(permitted("YARN_ENABLE_SCRIPTS=true yarn install --immutable")).isFalse();
  }

  @Test
  void npmStillRequiresIgnoreScripts() {
    assertThat(permitted("npm ci")).isFalse();
    assertThat(permitted("npm ci --ignore-scripts")).isTrue();
  }

  /**
   * An env-var prefix used to hide the whole install from this policy, because {@code tokens[0]}
   * was the assignment rather than the manager and the segment parsed as "not an install command".
   */
  @Test
  void envPrefixedInstallIsStillRecognisedAsAnInstall() {
    assertThat(DependencySecurityPolicy.parseInstallInvocation("CI=1 npm ci")).isNotNull();
    assertThat(permitted("CI=1 npm ci")).isFalse();
    assertThat(permitted("CI=1 npm ci --ignore-scripts")).isTrue();
  }

  @Test
  void npmConfigIgnoreScriptsEnvIsAccepted() {
    assertThat(permitted("NPM_CONFIG_IGNORE_SCRIPTS=1 npm ci")).isTrue();
    assertThat(permitted("NPM_CONFIG_IGNORE_SCRIPTS=0 npm ci")).isFalse();
  }

  // ---------------------------------------------------------------- RR-248

  @Test
  void registrySuffixLookalikeHostIsRejected() {
    assertThat(
            DependencySecurityPolicy.isAllowedRegistry(
                "https://registry.npmjs.org.evil.example", List.of("https://registry.npmjs.org")))
        .isFalse();
  }

  @Test
  void registryUserinfoTrickIsRejected() {
    assertThat(
            DependencySecurityPolicy.isAllowedRegistry(
                "https://registry.npmjs.org@evil.example/", List.of("https://registry.npmjs.org")))
        .isFalse();
  }

  @Test
  void registryPortMismatchIsRejected() {
    assertThat(
            DependencySecurityPolicy.isAllowedRegistry(
                "https://registry.npmjs.org:8443", List.of("https://registry.npmjs.org")))
        .isFalse();
  }

  @Test
  void registrySchemeDowngradeIsRejected() {
    assertThat(
            DependencySecurityPolicy.isAllowedRegistry(
                "http://registry.npmjs.org", List.of("https://registry.npmjs.org")))
        .isFalse();
  }

  @Test
  void exactAllowedRegistryIsAccepted() {
    assertThat(
            DependencySecurityPolicy.isAllowedRegistry(
                "https://registry.npmjs.org/", List.of("https://registry.npmjs.org")))
        .isTrue();
    assertThat(
            DependencySecurityPolicy.isAllowedRegistry(
                "https://registry.npmjs.org:443/scoped", List.of("https://registry.npmjs.org")))
        .isTrue();
  }

  @Test
  void allowedPathPrefixOnlyMatchesOnASegmentBoundary() {
    assertThat(
            DependencySecurityPolicy.isAllowedRegistry(
                "https://registry.npmjs.org/team-evil", List.of("https://registry.npmjs.org/team")))
        .isFalse();
    assertThat(
            DependencySecurityPolicy.isAllowedRegistry(
                "https://registry.npmjs.org/team/pkg", List.of("https://registry.npmjs.org/team")))
        .isTrue();
  }

  @Test
  void highGuardrailBlocksLookalikeRegistryFlag() {
    assertThat(
            permittedHigh(
                "npm ci --ignore-scripts --audit-level=high"
                    + " --registry=https://registry.npmjs.org.evil.example"))
        .isFalse();
  }

  @Test
  void offRegistryDetectionUsesHostNotPrefix() {
    // Previously the unanchored negative lookahead let this pass as "an npm registry URL".
    assertThat(permitted("npm ci --ignore-scripts https://registry.npmjs.org.evil.example/p.tgz"))
        .isFalse();
    assertThat(permitted("npm ci --ignore-scripts https://registry.npmjs.org/p/-/p-1.0.0.tgz"))
        .isTrue();
  }

  @Test
  void gitAndFileSourcesRemainBlocked() {
    assertThat(permitted("npm ci --ignore-scripts git+ssh://git@github.com/x/y.git")).isFalse();
  }

  /**
   * Wider than the RR-248 report: the old pattern required {@code file://}, so npm's ordinary
   * single-colon forms and the Git-resolving shorthands were never detected as off-registry.
   */
  @Test
  void singleColonFileAndGitShorthandSourcesAreAlsoBlocked() {
    assertThat(permitted("npm ci --ignore-scripts file:../local-pkg")).isFalse();
    assertThat(permitted("npm install --ignore-scripts --frozen-lockfile github:evil/pkg"))
        .isFalse();
    assertThat(permitted("npm install --ignore-scripts --frozen-lockfile gitlab:evil/pkg"))
        .isFalse();
  }

  @Test
  void ordinaryRegistryInstallIsStillPermitted() {
    assertThat(permitted("npm ci --ignore-scripts")).isTrue();
    assertThat(permitted("pnpm install --ignore-scripts --frozen-lockfile lodash@4.17.21"))
        .isTrue();
  }
}
