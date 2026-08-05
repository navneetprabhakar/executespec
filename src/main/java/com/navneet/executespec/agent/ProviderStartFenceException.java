package com.navneet.executespec.agent;

/** Terminal marker for loss of durable authority immediately before provider transport. */
final class ProviderStartFenceException extends RuntimeException {

  private ProviderStartFenceException(RuntimeException cause) {
    super("Durable provider-start fence rejected provider transport", cause);
  }

  static void signal(Runnable callback) {
    if (callback == null) return;
    try {
      callback.run();
    } catch (ProviderStartFenceException alreadyMarked) {
      throw alreadyMarked;
    } catch (RuntimeException failure) {
      throw new ProviderStartFenceException(failure);
    }
  }

  static boolean causedBy(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof ProviderStartFenceException) return true;
    }
    return false;
  }
}
