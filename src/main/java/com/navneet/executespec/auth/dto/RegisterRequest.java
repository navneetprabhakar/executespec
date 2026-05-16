package com.navneet.executespec.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    /**
     * Email — tightened beyond Hibernate Validator's default {@code @Email} (which accepts quoted
     * local-parts like {@code admin'--@foo}). This is a strict RFC-5321-lite allowlist of the
     * characters real email providers actually accept. Closes adversarial-test-report Finding 18.
     */
    @NotBlank(message = "email is required") @Email(message = "email must be a valid address") @Pattern(
            regexp = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$",
            message = "email must be a valid address")
        @Size(max = 254, message = "email must not exceed 254 characters") String email,
    @NotBlank(message = "firstName is required") @Size(min = 1, max = 100, message = "firstName must be 1-100 characters") @Pattern(regexp = "^[^<>]+$", message = "firstName must not contain < or >") String firstName,
    @NotBlank(message = "lastName is required") @Size(min = 1, max = 100, message = "lastName must be 1-100 characters") @Pattern(regexp = "^[^<>]+$", message = "lastName must not contain < or >") String lastName,

    // Bean Validation only checks length here; full complexity rules
    // (uppercase / lowercase / digit / special char / no whitespace)
    // run inside AuthService.validatePasswordStrength after this passes.
    @NotBlank(message = "password is required") @Size(
            min = 10,
            max = 128,
            message =
                "password must be 10-128 characters; needs uppercase, lowercase, digit, and special"
                    + " character")
        String password,

    /**
     * Wave 2.1 — attribution fields. All optional; frontend forwards whatever it captures from the
     * signup URL (utm_source, utm_campaign, ref). Length capped at 64/128 to fit the app_user
     * columns + avoid log spam.
     */
    @Size(max = 64, message = "signupSource must not exceed 64 characters") String signupSource,
    @Size(max = 64, message = "utmSource must not exceed 64 characters") String utmSource,
    @Size(max = 128, message = "utmCampaign must not exceed 128 characters") String utmCampaign) {
  /** Back-compat for callers pre-Wave-2.1 — no attribution. */
  public RegisterRequest(String email, String firstName, String lastName, String password) {
    this(email, firstName, lastName, password, null, null, null);
  }
}
