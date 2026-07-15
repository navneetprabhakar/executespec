package com.navneet.executespec.agentsession;

/** A client-correctable bounded subagent budget failure. */
public class SubagentBudgetExceededException extends IllegalStateException {
  private final String errorCode;

  public SubagentBudgetExceededException(String errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public String errorCode() {
    return errorCode;
  }
}
