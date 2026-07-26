package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlanGlobalErrorEnvelopeGateTest {

  @Test
  void rejectsR14RawFrameworkDetailAndIncompleteGlobalHandlerCoverage() {
    PlannedTask crud =
        task(
            "T4",
            "Implement warehouse and product CRUD API endpoints",
            "Raise HTTPException(404) for missing rows. Duplicate code or SKU MUST return HTTP 409"
                + " with JSON body {\"detail\":\"duplicate\"}.",
            List.of("backend/app/routers/warehouses.py"));
    PlannedTask globalHandler =
        task(
            "T8",
            "Add global error handling",
            "Create a global exception handler. Every error must use"
                + " {\"error\":{\"code\":\"STABLE_CODE\",\"message\":\"sanitized\","
                + "\"details\":{}}}. Register handlers for AppException and unhandled Exception.",
            List.of("backend/app/exception_handlers.py"));

    assertThat(PlanContractQualityGate.violations(plan(crud, globalHandler)))
        .anyMatch(
            value ->
                value.contains("T4")
                    && value.contains("raw framework {detail: ...}")
                    && value.contains("T8"))
        .contains(
            "T8: global error-envelope owner does not cover framework HTTP errors such as"
                + " HTTPException",
            "T8: global error-envelope owner does not cover framework request-validation errors");
  }

  @Test
  void rejectsR14FrontendConsumersThatBypassSharedNormalization() {
    PlannedTask globalHandler = completeGlobalHandler();
    PlannedTask apiClient =
        task(
            "T10",
            "Build shared frontend API client",
            "The axios response interceptor normalizes errors into"
                + " { code: string, message: string, details?: any }.",
            List.of("frontend/src/api/client.ts"));
    PlannedTask warehouseScreen =
        task(
            "T12",
            "Build warehouse screens",
            "For a 409 response, display the detail string from the response body.",
            List.of("frontend/src/pages/Warehouses.tsx"));
    PlannedTask orderScreen =
        task(
            "T14",
            "Build order screens",
            "On action failure consume response.data.error.code and response.data.error.message.",
            List.of("frontend/src/pages/Orders.tsx"));

    assertThat(
            PlanContractQualityGate.violations(
                plan(globalHandler, apiClient, warehouseScreen, orderScreen)))
        .anyMatch(
            value ->
                value.contains("T12")
                    && value.contains("raw response detail")
                    && value.contains("T10"))
        .anyMatch(
            value ->
                value.contains("T14")
                    && value.contains("nested wire envelope directly")
                    && value.contains("T10"));
  }

  @Test
  void acceptsGlobalFrameworkCoverageAndOneFrontendErrorBoundary() {
    PlannedTask globalHandler = completeGlobalHandler();
    PlannedTask producer =
        task(
            "T4",
            "Implement CRUD API",
            "Raise AppException with stable NOT_FOUND and DUPLICATE_RESOURCE codes; the global"
                + " handler owns the wire response.",
            List.of("backend/app/routers/resources.py"));
    PlannedTask apiClient =
        task(
            "T10",
            "Build shared frontend API client",
            "Unwrap the nested wire envelope in one axios interceptor and normalize all errors"
                + " into { code: string, message: string, details?: any }.",
            List.of("frontend/src/api/client.ts"));
    PlannedTask consumer =
        task(
            "T12",
            "Build management screen",
            "Catch ApiError and render error.message; branch on error.code for known conflicts.",
            List.of("frontend/src/pages/Resources.tsx"));

    assertThat(
            PlanContractQualityGate.violations(plan(globalHandler, producer, apiClient, consumer)))
        .isEmpty();
  }

  private static PlannedTask completeGlobalHandler() {
    return task(
        "T8",
        "Add global error handling",
        "Every non-2xx response uses"
            + " {\"error\":{\"code\":\"STABLE_CODE\",\"message\":\"sanitized\","
            + "\"details\":{}}}. The global exception handler registers AppException,"
            + " HTTPException, RequestValidationError, and unhandled Exception handlers.",
        List.of("backend/app/exception_handlers.py"));
  }

  private static Plan plan(PlannedTask... tasks) {
    return Plan.builder()
        .planTitle("r14 global error-envelope regression")
        .projectType("full_stack")
        .runtimeSurface(Plan.RuntimeSurface.HTTP)
        .tasks(List.of(tasks))
        .build();
  }

  private static PlannedTask task(
      String id, String title, String description, List<String> touchesFiles) {
    return PlannedTask.builder()
        .id(id)
        .title(title)
        .description(description)
        .complexity(PlannedTask.Complexity.MEDIUM)
        .touchesFiles(touchesFiles)
        .dependsOn(List.of())
        .hardConstraints(List.of())
        .meetsRequirements(List.of())
        .taskType(PlannedTask.TaskType.LEAF)
        .build();
  }
}
