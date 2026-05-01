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
            + "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; "
            + "img-src 'self' data:; "
            + "connect-src 'self' wss:; "
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
