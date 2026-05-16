package com.navneet.executespec.contact;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContactRequest(
    @NotBlank(message = "Subject must not be blank") @Size(max = 200, message = "Subject must not exceed 200 characters") String subject,
    @NotBlank(message = "Feedback body must not be blank") @Size(max = 5000, message = "Feedback body must not exceed 5000 characters") String body) {}
