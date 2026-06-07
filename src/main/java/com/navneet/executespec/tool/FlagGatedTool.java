package com.navneet.executespec.tool;

/**
 * Marker for a {@link Tool} whose binding into the model's tool schema is feature-flag-gated
 * (vix-leverage). {@link ToolRegistry#bind} / {@link ToolRegistry#bindForWorker} skip a flag-gated
 * tool whose {@link #enabled()} is false, so it is <b>ABSENT</b> from the schema (not
 * bound-but-inert) — per the RL-2026-05-061 lesson that a tool merely <i>present</i> in the schema
 * can cause reasoning-only stalls on schema-sensitive providers (DeepSeek). A default-OFF
 * flag-gated tool therefore keeps the worker tool set byte-identical until its flag is flipped.
 *
 * <p>Implementors: {@link BackgroundJobTool} (W4.2), {@link ReadCodeOutlineTool} (W3.1).
 */
public interface FlagGatedTool {

  /** True when this tool should be bound into the schema for the current run. */
  boolean enabled();
}
