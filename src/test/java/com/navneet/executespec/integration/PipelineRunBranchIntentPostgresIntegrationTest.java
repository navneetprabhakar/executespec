package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL proof for V373's immutable checkout/publication authority tuple. */
class PipelineRunBranchIntentPostgresIntegrationTest extends PostgresIntegrationBase {

  private static final String PREFIX = "branch-intent-it-";

  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM pipeline_run WHERE run_id LIKE ?", PREFIX + "%");
  }

  @Test
  void checkoutOnlyIsValidButPartialOrMismatchedPublicationAuthorityIsRejected() {
    String checkoutOnly = id();
    jdbc.update(
        "INSERT INTO pipeline_run (run_id,status,checkout_branch) VALUES (?,?,?)",
        checkoutOnly,
        "QUEUED",
        "feature/source");
    assertThat(
            jdbc.queryForObject(
                "SELECT checkout_branch FROM pipeline_run WHERE run_id=?",
                String.class,
                checkoutOnly))
        .isEqualTo("feature/source");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO pipeline_run"
                        + " (run_id,status,branch_override,checkout_branch) VALUES (?,?,?,?)",
                    id(),
                    "QUEUED",
                    "feature/pr-12",
                    "feature/pr-12"))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO pipeline_run"
                        + " (run_id,status,branch_override,checkout_branch,"
                        + " publication_repo_full_name,publication_pr_number,publication_head_sha)"
                        + " VALUES (?,?,?,?,?,?,?)",
                    id(),
                    "QUEUED",
                    "feature/pr-12",
                    "feature/wrong",
                    "acme/app",
                    12,
                    "1111111111111111111111111111111111111111"))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO pipeline_run"
                        + " (run_id,status,branch_override,checkout_branch,"
                        + " publication_repo_full_name,publication_pr_number,publication_head_sha)"
                        + " VALUES (?,?,?,?,?,?,?)",
                    id(),
                    "QUEUED",
                    "feature/pr-12",
                    "feature/pr-12",
                    "acme/app",
                    12,
                    "not-a-sha"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void exactPublicationAuthorityCannotBeMutatedAfterInsert() {
    String runId = id();
    jdbc.update(
        "INSERT INTO pipeline_run"
            + " (run_id,status,branch_override,checkout_branch,publication_repo_full_name,"
            + " publication_pr_number,publication_head_sha) VALUES (?,?,?,?,?,?,?)",
        runId,
        "QUEUED",
        "feature/pr-12",
        "feature/pr-12",
        "acme/app",
        12,
        "1111111111111111111111111111111111111111");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE pipeline_run SET checkout_branch=? WHERE run_id=?",
                    "feature/other",
                    runId))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE pipeline_run SET publication_head_sha=? WHERE run_id=?",
                    "2222222222222222222222222222222222222222",
                    runId))
        .isInstanceOf(DataAccessException.class);
  }

  private static String id() {
    return PREFIX + UUID.randomUUID();
  }
}
