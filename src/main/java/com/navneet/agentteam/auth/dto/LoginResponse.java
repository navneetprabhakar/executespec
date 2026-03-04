package com.navneet.agentteam.auth.dto;

public record LoginResponse(
        String accessToken,
        UserDto user
) {}
