package com.navneet.executespec.testgen.model;

public enum TestCategory {
  CRUD,
  AUTH,
  VALIDATION,
  PAGINATION,
  LIFECYCLE,
  ERROR_HANDLING,
  // Static HTML / Frontend categories (Playwright browser tests)
  UI_INTERACTION,
  NAVIGATION,
  PAGE_CONTENT,
  // CLI categories
  CLI_OUTPUT,
  CLI_ERROR
}
