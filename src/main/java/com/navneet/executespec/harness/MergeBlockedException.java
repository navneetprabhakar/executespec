package com.navneet.executespec.harness;

/** Thrown when a branch is blocked from merging (e.g. README.md not updated). */
public class MergeBlockedException extends RuntimeException {
    public MergeBlockedException(String message) {
        super(message);
    }
}
