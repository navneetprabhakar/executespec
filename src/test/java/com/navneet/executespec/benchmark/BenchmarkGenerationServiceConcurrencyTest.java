package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.engine.CodeGenEngine;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.swebench.SwebenchContainerStore;
import com.navneet.executespec.swebench.SwebenchInstanceLoader;
import com.navneet.executespec.swebench.SwebenchProperties;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class BenchmarkGenerationServiceConcurrencyTest {

  @Test
  void generationSlotHonorsConfiguredLimitAndReleases() {
    SwebenchProperties props = new SwebenchProperties();
    props.setMaxConcurrentRuns(1);
    props.setSubmitQueueWaitSeconds(0);
    BenchmarkGenerationService service = service(props);

    assertThatCode(() -> service.acquireGenerationSlot("first")).doesNotThrowAnyException();
    assertThatThrownBy(() -> service.acquireGenerationSlot("second"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    service.releaseGenerationSlot();

    assertThatCode(() -> service.acquireGenerationSlot("third")).doesNotThrowAnyException();
    service.releaseGenerationSlot();
  }

  private static BenchmarkGenerationService service(SwebenchProperties props) {
    return new BenchmarkGenerationService(
        props,
        mock(SwebenchInstanceLoader.class),
        mock(SwebenchContainerStore.class),
        mock(CodeGenEngine.class),
        mock(PipelineRunRepository.class),
        mock(RunCostTracker.class));
  }
}
