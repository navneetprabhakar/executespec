package com.navneet.executespec.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Feature-flagged security headers. Every sub-feature defaults to "on" so the app ships secure
 * out-of-the-box; individual headers can be disabled or tuned per environment via application.yml
 * overrides.
 */
@Component
@ConfigurationProperties(prefix = "app.security")
@Data
public class SecurityProperties {

  private Csp csp = new Csp();
  private Hsts hsts = new Hsts();
  private FrameOptions frameOptions = new FrameOptions();

  @Data
  public static class Csp {
    private boolean enabled = true;
    private String policy =
        "default-src 'self'; "
            + "script-src 'self' https://cdn.jsdelivr.net https://static.cloudflareinsights.com; "
            // 2026-05-10 mobile sweep iter-3 — `https://fonts.googleapis.com` added
            // to style-src so the JetBrains Mono stylesheet loaded by index.html
            // resolves. Pre-fix the CSP blocked the CSS file, the font @font-face
            // never registered, and every page fell back to system fonts that
            // render blurry on iOS Safari at 2x/3x DPR. font-src already allowed
            // fonts.gstatic.com (the actual .woff2 files), so this single domain
            // addition closes the gap without widening the surface meaningfully.
            + "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://fonts.googleapis.com; "
            + "img-src 'self' data:; "
            + "connect-src 'self' wss: https://cdn.jsdelivr.net; "
            + "font-src 'self' https://fonts.gstatic.com https://cdn.jsdelivr.net; "
            + "worker-src 'self' blob:; "
            + "frame-src 'self' https://*.run.app https://*.executespec.ai; "
            + "frame-ancestors 'self' https://*.executespec.ai https://*.run.app";
  }

  @Data
  public static class Hsts {
    private boolean enabled = true;
    private long maxAgeSeconds = 31_536_000; // 1 year
    private boolean includeSubDomains = true;
  }

  @Data
  public static class FrameOptions {
    private boolean enabled = true;

    /** DENY or SAMEORIGIN — use SAMEORIGIN to allow preview iframes */
    private String mode = "SAMEORIGIN";
  }
}
