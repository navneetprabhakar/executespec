package com.navneet.executespec.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record InviteRequest(
        @NotBlank @Email String email,
        String orgRole,          // defaults to DEVELOPER if null
        List<String> projectIds  // projects to auto-assign on accept
) {}
