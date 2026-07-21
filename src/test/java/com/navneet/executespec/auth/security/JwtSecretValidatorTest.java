package com.navneet.executespec.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RR-858 — the JWT boot gate must cover every shared environment and must test SECRECY, not only
 * strength.
 */
class JwtSecretValidatorTest {

  private JwtSecretValidator validator(String secret, List<String> denied) {
    JwtSecretValidator v = new JwtSecretValidator();
    ReflectionTestUtils.setField(v, "jwtSecret", secret);
    ReflectionTestUtils.setField(v, "deniedSecretDigests", denied);
    return v;
  }

  @Test
  void guardsEveryInternetReachableProfile() {
    Profile profile = JwtSecretValidator.class.getAnnotation(Profile.class);
    assertThat(profile).isNotNull();
    assertThat(profile.value())
        .as("dev and benchmark are shared, reachable deployments — not local")
        .containsExactlyInAnyOrder("prod", "dev", "benchmark");
  }

  @Test
  void missingSecretFailsBootRatherThanFallingBackToADefault() {
    assertThatThrownBy(() -> validator("", List.of()).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not configured");
  }

  @Test
  void shortSecretIsRejected() {
    assertThatThrownBy(() -> validator("too-short", List.of()).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("too short");
  }

  @Test
  void placeholderWordingIsRejected() {
    assertThatThrownBy(
            () ->
                validator("a-secret-that-must-be-replaced-before-any-deploy-0123", List.of())
                    .validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("placeholder");
  }

  /**
   * The whole point of the deny-list: a published secret is long, non-blank, and not worded like a
   * placeholder, so every strength check passes it. Only its identity gives it away.
   */
  @Test
  void anOperatorBurnedSecretIsRejectedEvenThoughItPassesEveryStrengthCheck() {
    String burned = "Zx7Q-strong-looking-but-published-secret-value-9182736450";
    assertThat(burned.length()).isGreaterThanOrEqualTo(32);
    assertThat(burned.toLowerCase()).doesNotContain("must-be");

    assertThatThrownBy(
            () ->
                validator(burned, List.of(JwtSecretValidator.sha256Hex(burned).toUpperCase()))
                    .validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("published");
  }

  /**
   * Burning a secret is one-way. This pins the built-in list so a future cleanup cannot quietly
   * drop an entry and re-permit a published value. Digests only — the plaintexts stay burned.
   */
  @Test
  @SuppressWarnings("unchecked")
  void everyPublishedDefaultStaysPermanentlyBurned() {
    Set<String> pinned =
        (Set<String>)
            ReflectionTestUtils.getField(JwtSecretValidator.class, "COMPROMISED_SECRET_DIGESTS");

    assertThat(pinned)
        .contains(
            "d110fe96843f893231f1af1d6293683bd9771eab3c47106d7b941dce1ade5d1d", // application.yml
            "56bde53456dbae8a42d91bc548131be9c0f97de7edacd1d572da0e5b10bf29c8", // dev
            "5efd301f4374ec62fd04598f3a8287a9099948ff30a33b116c0b3911ca407a18", // benchmark
            "ff00b1fc64baa81e7b73c41587166ce4079a1da9d96fbac21e2c542aa82412b2", // prod
            "0ce3d1ee9fa6b1da3ef4a19215e22df13b922506929348e1c0af29a006595e4a"); // later local
    assertThat(pinned).allSatisfy(digest -> assertThat(digest).hasSize(64).isLowerCase());
  }

  @Test
  void aFreshStrongSecretBoots() {
    assertThatCode(
            () -> validator("F3kq9Zt2Lp8Wn4Rc7Yb1Xd6Vs0Hg5Ju-fresh-rotation", List.of()).validate())
        .doesNotThrowAnyException();
  }
}
