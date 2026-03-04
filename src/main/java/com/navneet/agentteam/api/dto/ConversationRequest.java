package com.navneet.agentteam.api.dto;

import java.util.List;

public record ConversationRequest(List<ConversationMessage> messages) {}
