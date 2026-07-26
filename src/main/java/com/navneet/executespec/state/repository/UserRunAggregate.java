package com.navneet.executespec.state.repository;

/** Closed Spring Data projection for per-owner run and token aggregates. */
public interface UserRunAggregate {
  String getUid();

  long getRunCount();

  long getCompleted();

  long getFailed();

  long getTotalIn();

  long getTotalOut();
}
