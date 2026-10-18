package com.navneet.executespec.workspacegeneration;

import java.util.UUID;

/** Exact prefix identity read before resolving a correction contract and rechecked at admission. */
public record AcceptedWorkspaceFrontier(UUID attemptId, int taskIndex, String generation) {}
