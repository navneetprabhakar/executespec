package com.navneet.executespec.continuationfixture;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Keeps Web/CLI on the normal continuation approval URL while preventing any fixture worker/LLM
 * dispatch. Non-fixture runs always proceed into the production lifecycle service unchanged.
 */
@Aspect
@Profile({"local", "dev"})
@Component
@RequiredArgsConstructor
public class ContinuationFixtureApprovalAspect {

  private final ContinuationFixtureService fixtures;

  @Around(
      "execution(public *"
          + " com.navneet.executespec.integration.RunLifecycleService.approveContinuation(..)) &&"
          + " args(runId,taskId)")
  public Object approveOrProceed(ProceedingJoinPoint joinPoint, String runId, String taskId)
      throws Throwable {
    if (!fixtures.isFixtureRun(runId)) {
      return joinPoint.proceed();
    }
    return fixtures.approveProviderFreeFixture(runId, taskId);
  }
}
