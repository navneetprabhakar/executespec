package com.navneet.executespec.engine;

/** Explicit dispatch authority for normal turns and the one-shot terminal shutdown finalizer. */
record WorkerDispatchMode(boolean immediatelyPrior, boolean finalizer) {

  static final WorkerDispatchMode NORMAL = new WorkerDispatchMode(false, false);
  static final WorkerDispatchMode FINALIZER = new WorkerDispatchMode(false, true);

  static WorkerDispatchMode normal(boolean immediatelyPrior) {
    return immediatelyPrior ? new WorkerDispatchMode(true, false) : NORMAL;
  }
}
