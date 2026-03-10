package com.navneet.executespec.auth.dto;

public record LoginResponse(
        String accessToken,
        UserDto user
) {}
