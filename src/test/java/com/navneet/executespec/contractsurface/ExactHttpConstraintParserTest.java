package com.navneet.executespec.contractsurface;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.contractsurface.ExactHttpConstraintParser.Constraint;
import com.navneet.executespec.contractsurface.ExactHttpConstraintParser.Polarity;

import org.junit.jupiter.api.Test;

class ExactHttpConstraintParserTest {

  @Test
  void bindsMethodNotAllowedToTheExactRouteOccurrence() {
    List<Constraint> constraints =
        ExactHttpConstraintParser.parse(
            "GET /api/system/ping returns ok; prove GET /api/system/ping succeeds and POST "
                + "/api/system/ping is rejected with HTTP 405.");

    assertThat(constraints)
        .containsExactly(
            new Constraint("GET", "/api/system/ping", Polarity.REQUIRED),
            new Constraint("GET", "/api/system/ping", Polarity.REQUIRED),
            new Constraint("POST", "/api/system/ping", Polarity.METHOD_NOT_ALLOWED));
  }

  @Test
  void ordinaryErrorHandlingDoesNotMakeTheProducerRouteForbidden() {
    assertThat(
            ExactHttpConstraintParser.parse(
                "POST /items rejects invalid payloads with 400 and returns 201 otherwise"))
        .containsExactly(new Constraint("POST", "/items", Polarity.REQUIRED));
    assertThat(
            ExactHttpConstraintParser.parse(
                "POST /login is rejected without a valid CSRF token and succeeds otherwise"))
        .containsExactly(new Constraint("POST", "/login", Polarity.REQUIRED));
  }

  @Test
  void removalIsExcludedAndPrefixMethodDenialIsClassified() {
    assertThat(ExactHttpConstraintParser.parse("Remove POST /legacy safely")).isEmpty();
    assertThat(ExactHttpConstraintParser.parse("Do not support DELETE /items"))
        .containsExactly(new Constraint("DELETE", "/items", Polarity.METHOD_NOT_ALLOWED));
  }
}
