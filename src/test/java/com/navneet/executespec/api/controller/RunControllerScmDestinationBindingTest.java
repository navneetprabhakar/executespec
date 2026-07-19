package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RunControllerScmDestinationBindingTest {

  @Test
  void absentDestinationOverrideKeepsTheDurableRunRepositoryAuthoritative() {
    assertThatCode(() -> RunController.rejectRepositoryDestinationOverride(null))
        .doesNotThrowAnyException();
    assertThatCode(() -> RunController.rejectRepositoryDestinationOverride("  "))
        .doesNotThrowAnyException();
  }

  @Test
  void callerCannotRedirectStoredCredentialsToAnotherRepositoryOrigin() {
    for (String untrusted :
        new String[] {
          "https://github.com/acme/other.git",
          "https://github.com.attacker.example/acme/repo.git",
          "https://token@attacker.example/repo.git",
          "https://bitbucket.org/acme/other.git"
        }) {
      assertThatThrownBy(() -> RunController.rejectRepositoryDestinationOverride(untrusted))
          .isInstanceOfSatisfying(
              ResponseStatusException.class,
              error -> {
                org.assertj.core.api.Assertions.assertThat(error.getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
                org.assertj.core.api.Assertions.assertThat(error.getReason())
                    .doesNotContain(untrusted);
              });
    }
  }
}
