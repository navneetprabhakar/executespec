package com.navneet.executespec.api.advice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.navneet.executespec.router.ProjectRoutingException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

class ValidationAdviceReachabilityTest {

  @Test
  void reachableControllerStatusesRemainControlledAndDoNotFallThroughTo500() throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(new StatusFixtureController())
            .setControllerAdvice(new ValidationAdvice(), new GlobalExceptionAdvice())
            .build();

    for (HttpStatus expected :
        new HttpStatus[] {
          HttpStatus.FORBIDDEN,
          HttpStatus.NOT_FOUND,
          HttpStatus.CONFLICT,
          HttpStatus.SERVICE_UNAVAILABLE
        }) {
      mvc.perform(get("/fixture/status/{status}", expected.value()))
          .andExpect(status().is(expected.value()))
          .andExpect(jsonPath("$.status").value(expected.value()))
          .andExpect(jsonPath("$.message").value("controlled " + expected.value()));
    }
  }

  @Test
  void reachableProjectRouteFailureCarriesStableCodeAndLeakSafeCopy() throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(new StatusFixtureController())
            .setControllerAdvice(new ValidationAdvice(), new GlobalExceptionAdvice())
            .build();

    mvc.perform(get("/fixture/project-route"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("service_unavailable"))
        .andExpect(jsonPath("$.errorCode").value("MODEL_MATRIX_UNAVAILABLE"))
        .andExpect(jsonPath("$.message").value("Project AI routing is temporarily unavailable."))
        .andExpect(
            jsonPath("$.message")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
  }

  @Test
  void bothProjectProviderConflictsCarryStableSafeCodes() throws Exception {
    var mvc = mvc();

    mvc.perform(get("/fixture/project-route/provider-missing"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("PROJECT_PROVIDER_NOT_CONFIGURED"))
        .andExpect(
            jsonPath("$.message").value("Choose an AI provider in project or account settings."));
    mvc.perform(get("/fixture/project-route/provider-invalid"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("PROJECT_PROVIDER_INVALID"))
        .andExpect(
            jsonPath("$.message")
                .value("Choose a supported AI provider in project or account settings."));
  }

  @Test
  void frameworkClientErrorsKeepExactJsonStatusInsteadOfFallingThroughTo500() throws Exception {
    var mvc = mvc();

    mvc.perform(post("/fixture/json").contentType(MediaType.TEXT_PLAIN).content("{}"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.error").value("unsupported_media_type"));
    mvc.perform(get("/fixture/json").accept(MediaType.APPLICATION_PDF))
        .andExpect(status().isNotAcceptable())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error").value("not_acceptable"));
    mvc.perform(get("/fixture/header"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Missing required header 'X-Fixture'"));
    mvc.perform(get("/fixture/cookie"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Missing required cookie 'fixture'"));
    mvc.perform(multipart("/fixture/part"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Missing required request part 'file'"));
  }

  private static org.springframework.test.web.servlet.MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(new StatusFixtureController())
        .setControllerAdvice(new ValidationAdvice(), new GlobalExceptionAdvice())
        .build();
  }

  @RestController
  static class StatusFixtureController {

    @GetMapping("/fixture/status/{status}")
    void status(@PathVariable int status) {
      throw new ResponseStatusException(HttpStatus.valueOf(status), "controlled " + status);
    }

    @GetMapping("/fixture/project-route")
    void projectRoute() {
      throw ProjectRoutingException.modelMatrixUnavailable(
          new IllegalStateException("secret matrix row"));
    }

    @GetMapping("/fixture/project-route/provider-missing")
    void providerMissing() {
      throw ProjectRoutingException.providerNotConfigured();
    }

    @GetMapping("/fixture/project-route/provider-invalid")
    void providerInvalid() {
      throw ProjectRoutingException.providerInvalid();
    }

    @GetMapping(value = "/fixture/json", produces = MediaType.APPLICATION_JSON_VALUE)
    String getJson() {
      return "{}";
    }

    @PostMapping(value = "/fixture/json", consumes = MediaType.APPLICATION_JSON_VALUE)
    void postJson(@RequestBody String ignored) {}

    @GetMapping("/fixture/header")
    void header(@RequestHeader("X-Fixture") String ignored) {}

    @GetMapping("/fixture/cookie")
    void cookie(@CookieValue("fixture") String ignored) {}

    @PostMapping(value = "/fixture/part", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    void part(@RequestPart("file") byte[] ignored) {}
  }
}
