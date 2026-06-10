package com.navneet.executespec.prreview.inbox;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.chat.ApplyService;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PrCommentInboxControllerGuardTest {

  private final PrCommentInboxService service = mock(PrCommentInboxService.class);
  private final RunAccessGuard guard = mock(RunAccessGuard.class);
  private final PrCommentInboxController controller =
      new PrCommentInboxController(service, guard, mock(ApplyService.class));
  private final AppUserDetails user = mock(AppUserDetails.class);

  @Test
  void dismissRequiresRunWriteAccess() {
    when(service.dismissForRun(7L, "run-1")).thenReturn(Optional.empty());

    controller.dismiss("run-1", 7L, user);

    verify(guard).requireRunWriteAccess("run-1", user);
    verify(guard, never()).requireRunAccess("run-1", user);
  }

  @Test
  void applyRequiresRunWriteAccess() {
    when(service.findForRun(8L, "run-2")).thenReturn(Optional.empty());

    try {
      controller.apply("run-2", 8L, user);
    } catch (ResponseStatusException expected) {
      // expected: no comment row, but guard must be the write guard before lookup.
    }

    verify(guard).requireRunWriteAccess("run-2", user);
    verify(guard, never()).requireRunAccess("run-2", user);
  }
}
