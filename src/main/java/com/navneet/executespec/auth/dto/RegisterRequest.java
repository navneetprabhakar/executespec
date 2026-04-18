package com.navneet.executespec.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address")
        String email,

        @NotBlank(message = "firstName is required")
        @Size(min = 1, max = 100, message = "firstName must be 1-100 characters")
        String firstName,

        @NotBlank(message = "lastName is required")
        @Size(min = 1, max = 100, message = "lastName must be 1-100 characters")
        String lastName,

        // Bean Validation only checks length here; full complexity rules
        // (uppercase / lowercase / digit / special char / no whitespace)
        // run inside AuthService.validatePasswordStrength after this passes.
        @NotBlank(message = "password is required")
        @Size(min = 10, max = 128, message = "password must be 10-128 characters; needs uppercase, lowercase, digit, and special character")
        String password
) {}
