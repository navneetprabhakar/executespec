package com.navneet.executespec.standards;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.navneet.executespec.standards.model.StandardsParser;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OrgStandardControllerValidationTest {

  private final OrgStandardController controller =
      new OrgStandardController(mock(OrgStandardRepository.class), new StandardsParser());

  @Test
  void rejectsMalformedYamlBeforePersistence() {
    assertThatThrownBy(() -> controller.validateStandardBody("secure-shell", "- id: ["))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex ->
                org.assertj.core.api.Assertions.assertThat(ex.getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void rejectsYamlWhoseIdDoesNotMatchTheRowIdentity() {
    assertThatThrownBy(
            () ->
                controller.validateStandardBody(
                    "secure-shell",
                    """
                    - id: different-policy
                      severity: BLOCKING
                      description: Prevent unsafe shell usage
                      enforcement:
                        type: file-pattern
                        pattern: '**/*.sh'
                    """))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex ->
                org.assertj.core.api.Assertions.assertThat(ex.getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void rejectsMultiplePoliciesInOneOrgStandardRow() {
    assertThatThrownBy(
            () ->
                controller.validateStandardBody(
                    "one",
                    """
                    - id: one
                      severity: WARNING
                      description: One
                      enforcement:
                        type: file-pattern
                        pattern: one
                    - id: two
                      severity: WARNING
                      description: Two
                      enforcement:
                        type: file-pattern
                        pattern: two
                    """))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex ->
                org.assertj.core.api.Assertions.assertThat(ex.getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }
}
