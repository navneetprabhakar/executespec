package com.navneet.executespec.harness;

import java.util.concurrent.CancellationException;

/** Cancellation proven by the durable run fence rather than only a thread interrupt. */
final class DurableRunCancellationException extends CancellationException {
  DurableRunCancellationException(String message) {
    super(message);
  }
}
