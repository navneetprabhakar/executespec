package com.navneet.executespec.api.dto;

import java.util.List;

public record ConversationRequest(List<ConversationMessage> messages) {}
