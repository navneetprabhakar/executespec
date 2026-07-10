package com.navneet.executespec.agentsession;

/** Durable local-hands client lifecycle states recorded against an agent session action. */
public enum LocalHandsState {
  AWAITING_CLIENT,
  CLIENT_RECONNECTED,
  CLIENT_PROTOCOL_ERROR,
  CLIENT_TIMEOUT,
  CLIENT_CANCELLED,
  COMPLETED
}
