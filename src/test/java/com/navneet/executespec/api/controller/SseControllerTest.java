package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navneet.executespec.auth.security.JwtUtil;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.sse.SseBroadcaster;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseControllerTest {

  private final SseBroadcaster broadcaster = mock(SseBroadcaster.class);
  private final RunAccessGuard guard = mock(RunAccessGuard.class);
  private final JwtUtil jwtUtil = mock(JwtUtil.class);
  private final SseController controller = new SseController(broadcaster, guard, jwtUtil);

  @Test
  void queryLastEventIdIsUsedWhenHeaderIsUnavailable() {
    var emitter = new SseEmitter();
    when(jwtUtil.validateSseToken("token-1", "run-1")).thenReturn("user-1");
    when(broadcaster.subscribe("run-1", "17")).thenReturn(emitter);

    var actual = controller.subscribe("run-1", "token-1", "17", null, null);

    assertThat(actual).isSameAs(emitter);
    verify(broadcaster).subscribe("run-1", "17");
  }

  @Test
  void headerLastEventIdWinsOverQueryFallback() {
    var emitter = new SseEmitter();
    when(jwtUtil.validateSseToken("token-1", "run-1")).thenReturn("user-1");
    when(broadcaster.subscribe("run-1", "19")).thenReturn(emitter);

    controller.subscribe("run-1", "token-1", "17", "19", null);

    verify(broadcaster).subscribe("run-1", "19");
  }
}
