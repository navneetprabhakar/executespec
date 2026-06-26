package com.navneet.executespec.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Pins the new {@link RunRequest#branch()} + {@link RunRequest#prReviewComments()} fields
 * introduced by Fix M (2026-05-03, run a076c876 incremental-update gap).
 *
 * <p>Diagnosis: {@code LaunchFromChatModal} was sending {@code body.branch = trimmedBranch} on
 * Launch, but {@code RunRequest} had no {@code branch} field — Jackson silently dropped it. The
 * resulting V2 run cloned each repo on its DEFAULT branch (not the user-typed PR branch) + pushed
 * to a fresh {@code executespec/<runIdShort>}, opening a NEW PR instead of updating the existing
 * one. End-to-end "spawn a follow-up run on this PR's branch" was non-functional.
 *
 * <p>This test asserts the deserialisation round-trip end-to-end so the field can't be silently
 * removed again. Both the canonical {@code branch} field and the {@code branchOverride} alias are
 * accepted (forward-compat for external callers).
 */
class RunRequestBranchAndPrCommentsTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void branch_field_is_deserialised() throws Exception {
    String json =
        "{\"userRequest\":\"address review comments\"," + "\"branch\":\"executespec/abc1234\"}";
    RunRequest req = mapper.readValue(json, RunRequest.class);

    assertThat(req.branch()).isEqualTo("executespec/abc1234");
    assertThat(req.userRequest()).isEqualTo("address review comments");
  }

  @Test
  void branchOverride_alias_is_accepted() throws Exception {
    String json =
        "{\"userRequest\":\"hotfix on existing branch\","
            + "\"branchOverride\":\"feature/work-in-progress\"}";
    RunRequest req = mapper.readValue(json, RunRequest.class);

    assertThat(req.branch()).isEqualTo("feature/work-in-progress");
  }

  @Test
  void prReviewComments_field_is_deserialised() throws Exception {
    String json =
        "{\"userRequest\":\"fix\","
            + "\"prReviewComments\":\"Reviewer asks: add validation on email field; "
            + "make password hashing async\"}";
    RunRequest req = mapper.readValue(json, RunRequest.class);

    assertThat(req.prReviewComments())
        .contains("add validation on email field")
        .contains("make password hashing async");
  }

  @Test
  void all_fields_round_trip_together() throws Exception {
    String json =
        "{\"userRequest\":\"polish\","
            + "\"projectId\":\"abc-123\","
            + "\"branch\":\"executespec/foo\","
            + "\"prReviewComments\":\"see comments\"}";
    RunRequest req = mapper.readValue(json, RunRequest.class);

    assertThat(req.userRequest()).isEqualTo("polish");
    assertThat(req.projectId()).isEqualTo("abc-123");
    assertThat(req.branch()).isEqualTo("executespec/foo");
    assertThat(req.prReviewComments()).isEqualTo("see comments");
  }

  @Test
  void continuationConfirmationFlag_is_deserialised() throws Exception {
    String json =
        "{\"userRequest\":\"ship safely\","
            + "\"requireConfirmationBeforeExpensiveContinuation\":true}";
    RunRequest req = mapper.readValue(json, RunRequest.class);

    assertThat(req.requireConfirmationBeforeExpensiveContinuation()).isTrue();
  }

  @Test
  void missing_fields_are_null_not_blank() throws Exception {
    String json = "{\"userRequest\":\"plain run\"}";
    RunRequest req = mapper.readValue(json, RunRequest.class);

    assertThat(req.branch()).isNull();
    assertThat(req.prReviewComments()).isNull();
  }
}
