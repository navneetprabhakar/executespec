package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutlinePlannerSingleShotSurfaceTest {

  @Test
  void shortWebAppRequestMeansBrowserUiRatherThanApi() {
    Plan.RuntimeSurface surface =
        SingleShotSurfaceClassifier.infer("lets create a simple web todo app");

    assertThat(surface).isEqualTo(Plan.RuntimeSurface.STATIC_WEB);
    assertThat(SingleShotSurfaceClassifier.projectType(surface)).isEqualTo("node-frontend");
  }

  @Test
  void explicitApiOrBackendRequestStillMeansHttp() {
    assertThat(SingleShotSurfaceClassifier.infer("create a simple web todo REST API"))
        .isEqualTo(Plan.RuntimeSurface.HTTP);
    assertThat(SingleShotSurfaceClassifier.infer("create a backend server for todo items"))
        .isEqualTo(Plan.RuntimeSurface.HTTP);
  }

  @Test
  void recognizesOtherExplicitRuntimeSurfaces() {
    assertThat(SingleShotSurfaceClassifier.infer("create a todo CLI"))
        .isEqualTo(Plan.RuntimeSurface.CLI);
    assertThat(SingleShotSurfaceClassifier.infer("create a reusable todo library"))
        .isEqualTo(Plan.RuntimeSurface.LIBRARY);
  }

  @Test
  void negatedHttpConstraintDoesNotOverrideCliSurface() {
    assertThat(
            SingleShotSurfaceClassifier.infer(
                "Create a TypeScript CLI. Do not create an HTTP server."))
        .isEqualTo(Plan.RuntimeSurface.CLI);
  }
}
