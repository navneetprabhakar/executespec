package com.navneet.executespec.agent;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deadlines and rollout switches for streamed provider transports.
 *
 * <h2>Why streaming exists here</h2>
 *
 * A non-streaming provider call yields exactly one bit of information, and only when it expires.
 * "The model is generating and will answer at 239s" and "the socket is dead and will never answer"
 * are indistinguishable until the deadline, which forces one timeout value to be simultaneously
 * generous enough for slow success and short enough to fail fast. That is unsatisfiable, and dev
 * run 350fd2fc is what it looks like when you lose the bet: three consecutive outline attempts, 240
 * seconds each, ZERO tokens in either direction, twelve minutes spent to learn nothing.
 *
 * <p>Streaming replaces that single deadline with independent ones, each answering a question it
 * can actually answer.
 *
 * <h2>Why time-to-first-token is the primary signal</h2>
 *
 * TTFT is the only deadline that cleanly separates "not answering" from "answering slowly", so it
 * is short. The inter-chunk gap is deliberately NOT treated as an equally strong signal: Anthropic
 * documents that when tools are in use "there may be delays between streaming events while the
 * model is working", because current models emit one complete key/value of the tool input at a
 * time. Our planner and worker calls are forced tool calls, so an aggressive inter-chunk timeout
 * would kill healthy generations — the exact false-positive class this work exists to remove. It is
 * therefore a loose backstop for a genuinely dead mid-stream socket, not a latency control.
 *
 * <h2>What streaming does NOT buy us</h2>
 *
 * Partial recovery. Anthropic is explicit that "tool use and extended thinking blocks cannot be
 * partially recovered" — only a text block can be resumed. Every call that matters here ends in a
 * forced tool call, so a stream that dies mid-tool is still a total loss of that call. Streaming
 * buys FAST DETECTION (fail in TTFT seconds, then retry or fail over), not partial credit.
 */
@Component
public class StreamingTransportPolicy {

  /**
   * OpenAI-wire transports (OpenAI, OpenRouter, DeepSeek, Fireworks). This is the wire the observed
   * failure occurred on, so it is the transport this work exists to fix — but it ships OFF.
   *
   * <p>Two things must be true before it is turned on, and neither is proven yet. (1) The 60-second
   * time-to-first-token below is REASONED, not measured: no live provider call has been made
   * through this transport, so a slow-but-healthy route could be killed by a deadline chosen from
   * the failing route's behaviour. (2) A stream interrupted before its final usage chunk currently
   * reaches the cost tracker with no usage node at all; until that is explicitly recorded as
   * unknown rather than absent, enabling this changes billing evidence on every OpenAI-wire call.
   *
   * <p>Flipping it is therefore an explicit decision backed by a live measurement, not a default:
   * {@code EXECUTESPEC_LLM_STREAMING_OPENAI_WIRE_ENABLED=true}. While it is off, every OpenAI-wire
   * call takes the existing non-streaming path unchanged.
   */
  @Value("${executespec.llm.streaming.openai-wire.enabled:false}")
  private boolean openAiWireEnabled = false;

  /** Gemini. Defaults OFF until its own live validation; see {@code GeminiNativeToolClient}. */
  @Value("${executespec.llm.streaming.gemini.enabled:false}")
  private boolean geminiEnabled = false;

  /** Anthropic. Defaults OFF until its own live validation. */
  @Value("${executespec.llm.streaming.anthropic.enabled:false}")
  private boolean anthropicEnabled = false;

  /**
   * No first byte within this window means the route is not answering. Measured evidence: healthy
   * outline calls on a reasoning model produced their first tokens well inside a minute, while the
   * dead route produced nothing in 240s three times running.
   */
  @Value("${executespec.llm.streaming.time-to-first-token-seconds:60}")
  private int timeToFirstTokenSeconds = 60;

  /**
   * Loose backstop for a socket that dies mid-generation. Must stay well above the legitimate gaps
   * documented for tool-input streaming; it is not a latency control.
   */
  @Value("${executespec.llm.streaming.inter-chunk-seconds:180}")
  private int interChunkSeconds = 180;

  /**
   * Ceiling on a whole streamed call. Generous because it is only consumed while tokens are
   * actually arriving — a dead route is caught by TTFT long before this.
   */
  @Value("${executespec.llm.streaming.total-seconds:900}")
  private int totalSeconds = 900;

  public boolean openAiWireEnabled() {
    return openAiWireEnabled;
  }

  public boolean geminiEnabled() {
    return geminiEnabled;
  }

  public boolean anthropicEnabled() {
    return anthropicEnabled;
  }

  public Duration timeToFirstToken() {
    return Duration.ofSeconds(positive(timeToFirstTokenSeconds, 60));
  }

  public Duration interChunk() {
    return Duration.ofSeconds(positive(interChunkSeconds, 180));
  }

  /**
   * The total ceiling for one streamed call. A caller-supplied per-call read timeout may TIGHTEN
   * this (the planner deliberately bounds itself) but never widen it, matching the router's
   * existing rule that a caller may narrow a transport window and never extend it.
   */
  public Duration totalFor(Duration callerCeiling) {
    Duration configured = Duration.ofSeconds(positive(totalSeconds, 900));
    if (callerCeiling == null || callerCeiling.isZero() || callerCeiling.isNegative()) {
      return configured;
    }
    return callerCeiling.compareTo(configured) < 0 ? callerCeiling : configured;
  }

  private static int positive(int value, int fallback) {
    return value > 0 ? value : fallback;
  }
}
