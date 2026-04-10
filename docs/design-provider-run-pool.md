# Design: Per-Provider Run Pool with Priority Queuing

> Status: DRAFT | Author: Claude | Date: 2026-04-10

## Problem

The current `PipelineHarness` uses a **single shared thread pool** and **single semaphore** for all concurrent runs regardless of AI provider. This causes:

1. **Cross-provider failure cascade** — when Google rate-limits, Anthropic and OpenAI runs are also starved of thread slots
2. **No isolation** — one slow provider blocks all others under backpressure (CallerRunsPolicy)
3. **Global queue position** — users see position relative to all runs, not their provider's queue
4. **E2E evidence** — running 3 concurrent runs (one per provider), Google and OpenAI simple specs failed with "Single-shot worker failed" while Anthropic succeeded

## Solution: Two-Layer Gating

```
              ┌──────────────────────┐
              │  Global Semaphore    │  ← existing max-runs cap (infrastructure limit)
              │  (max-runs permits)  │
              └──────┬───────────────┘
                     │
        ┌────────────┼────────────────┐
        ▼            ▼                ▼
  ┌──────────┐ ┌──────────┐   ┌──────────┐
  │ ANTHROPIC│ │  GOOGLE  │   │  OPENAI  │
  │ Pool (3) │ │ Pool (3) │   │ Pool (3) │
  │ Queue:15 │ │ Queue:10 │   │ Queue:10 │
  └──────────┘ └──────────┘   └──────────┘
       │            │               │
  PriorityQueue PriorityQueue  PriorityQueue
  ENT > PRO > FREE  (per pool)
```

**Layer 1 (Global):** Existing `runSlots` semaphore stays — prevents total system overload (DB, memory, disk).  
**Layer 2 (Per-Provider):** New `ProviderRunPool` per provider — isolates failures, enables independent queuing.

## New Classes

### 1. `ProviderRunPoolProperties`

**File:** `src/main/java/com/navneet/executespec/config/ProviderRunPoolProperties.java`

```java
@ConfigurationProperties(prefix = "agent.provider-pools")
@Data
public class ProviderRunPoolProperties {
    private int defaultConcurrency = 3;
    private int defaultQueueCapacity = 10;
    private Map<String, ProviderPoolConfig> providers = new HashMap<>();

    @Data
    public static class ProviderPoolConfig {
        private int concurrency = 3;
        private int queueCapacity = 10;
    }

    public ProviderPoolConfig forProvider(LlmProvider provider) {
        return providers.getOrDefault(provider.name(), defaultConfig());
    }

    private ProviderPoolConfig defaultConfig() {
        var c = new ProviderPoolConfig();
        c.setConcurrency(defaultConcurrency);
        c.setQueueCapacity(defaultQueueCapacity);
        return c;
    }
}
```

### 2. `ProviderQueueEntry`

**File:** `src/main/java/com/navneet/executespec/harness/ProviderQueueEntry.java`

```java
public record ProviderQueueEntry(
    String runId,
    int tierPriority,      // 0=ENTERPRISE, 1=PRO, 2=FREE
    Instant submittedAt,
    Runnable task
) implements Comparable<ProviderQueueEntry> {

    @Override
    public int compareTo(ProviderQueueEntry other) {
        int cmp = Integer.compare(this.tierPriority, other.tierPriority);
        return cmp != 0 ? cmp : this.submittedAt.compareTo(other.submittedAt);
    }

    public static int tierToPriority(String userTier) {
        return switch (userTier != null ? userTier.toUpperCase() : "FREE") {
            case "ENTERPRISE" -> 0;
            case "PRO" -> 1;
            default -> 2;  // FREE
        };
    }
}
```

### 3. `ProviderRunPool`

**File:** `src/main/java/com/navneet/executespec/harness/ProviderRunPool.java`

Encapsulates per-provider isolation:

- `Semaphore providerSlots` — permits = configured concurrency
- `PriorityBlockingQueue<ProviderQueueEntry> queue` — bounded, priority-ordered
- Internal dispatch threads (virtual threads via `Thread.ofVirtual()`) — pull from queue, acquire semaphore, execute

**Key methods:**

```java
void submit(String runId, String userTier, Instant submittedAt, Runnable task)
    // Wraps into ProviderQueueEntry, offers to queue.
    // Throws ProviderQueueFullException if queue at capacity.

boolean hasCapacity()
    // providerSlots.availablePermits() > 0

int queueDepth()        // queued entries waiting
int activeRuns()        // currently executing
int queuePositionFor(String runId)  // 1-based position in queue

void shutdown()         // graceful shutdown
```

**Internal dispatch loop (per virtual thread):**

```java
while (!shutdown) {
    ProviderQueueEntry entry = queue.take();   // blocks
    queuedCount.decrementAndGet();
    providerSlots.acquire();                    // blocks until slot
    activeCount.incrementAndGet();
    try {
        // Check if run was cancelled while queued
        if (stateStore.getStatus(entry.runId()) == CANCELLED) continue;
        entry.task().run();
    } finally {
        activeCount.decrementAndGet();
        providerSlots.release();
    }
}
```

### 4. `ProviderRunPoolManager`

**File:** `src/main/java/com/navneet/executespec/harness/ProviderRunPoolManager.java`

```java
@Component
@RequiredArgsConstructor
public class ProviderRunPoolManager {
    private final ProviderRunPoolProperties properties;
    private final MeterRegistry meterRegistry;
    private final Map<LlmProvider, ProviderRunPool> pools = new EnumMap<>(LlmProvider.class);

    @PostConstruct
    void init() {
        for (LlmProvider provider : LlmProvider.values()) {
            var config = properties.forProvider(provider);
            var pool = new ProviderRunPool(provider, config.getConcurrency(), config.getQueueCapacity());
            pools.put(provider, pool);

            // Micrometer gauges
            Gauge.builder("executespec.provider.pool.active", pool, ProviderRunPool::activeRuns)
                .tag("provider", provider.name()).register(meterRegistry);
            Gauge.builder("executespec.provider.pool.queued", pool, ProviderRunPool::queueDepth)
                .tag("provider", provider.name()).register(meterRegistry);
        }
    }

    public ProviderRunPool poolFor(LlmProvider provider) { return pools.get(provider); }
    public ProviderRunPool poolFor(String aiProvider)     { /* safe parse + default to OPENAI */ }
    public int totalActiveRuns()  { return pools.values().stream().mapToInt(ProviderRunPool::activeRuns).sum(); }
    public int totalQueuedRuns()  { return pools.values().stream().mapToInt(ProviderRunPool::queueDepth).sum(); }

    @PreDestroy
    void shutdown() { pools.values().forEach(ProviderRunPool::shutdown); }
}
```

## YAML Configuration

```yaml
agent:
  provider-pools:
    default-concurrency: 3
    default-queue-capacity: 10
    providers:
      ANTHROPIC:
        concurrency: 3
        queue-capacity: 15
      GOOGLE:
        concurrency: 3
        queue-capacity: 10
      OPENAI:
        concurrency: 3
        queue-capacity: 10
```

## Dispatch Flow Change

### Current (PipelineHarness.submitRun)

```
1. runSlots.tryAcquire()          ← single global semaphore
2. If no slot → QUEUED, global queue position, submit to shared executor
3. If slot → submit to shared executor
4. Executor has CallerRunsPolicy backpressure
```

### New

```
1. runSlots.tryAcquire()          ← global semaphore stays (infrastructure cap)
2. If no global slot → QUEUED globally, return
3. pool = poolManager.poolFor(run.getAiProviderEnum())
4. pool.submit(runId, userTier, now(), () -> executeRunAsync(...))
5. If pool queue full → release global slot, return 429
6. Pool internally: dequeue → acquire provider slot → execute
7. On completion: release provider slot → release global slot
```

### Entry Points to Modify

| Method | Line (approx) | Change |
|--------|---------------|--------|
| `submitRun()` | ~521 | Route to provider pool after global gate |
| `startIterationRun()` | ~1459 | Same pattern |
| `resumeRun()` | ~830 | Same pattern |
| `executeApplySuggestion()` | ~1341 | Same pattern |
| `onBatchComplete()` | ~3631 | Same pattern |
| `countQueued()` | ~3198 | Delegate to `poolManager.totalQueuedRuns()` |

## Queue Position

- `queuePosition` on `PipelineRun` becomes **per-provider** position
- When queued: `run.setQueuePosition(pool.queueDepth() + 1)`
- SSE event `RunEvent.queued(runId, position)` — no frontend change needed
- `ProviderRunPool.queuePositionFor(runId)` gives real-time position

## Metrics

| Metric | Tags | Source |
|--------|------|--------|
| `executespec.provider.pool.active` | `provider` | Active runs per provider |
| `executespec.provider.pool.queued` | `provider` | Queue depth per provider |
| `executespec.provider.pool.capacity` | `provider` | Configured concurrency |
| `executespec.run.queued` | (global) | `poolManager.totalQueuedRuns()` |
| `executespec.run.active` | (global) | `poolManager.totalActiveRuns()` |

## Edge Cases

### 1. Provider not yet known at submission
The `aiProvider` field defaults to `"OPENAI"` on the entity. It's always set by the time dispatch happens (line 480-502 of submitRun). Safe.

### 2. Multi-instance (MIG)
Queue is in-memory per JVM. For multi-instance, each instance independently respects configured concurrency. Redis-backed global queue adds complexity for marginal benefit — defer to future iteration.

### 3. Run cancelled while queued
Dispatch loop checks status after dequeue: `if (status == CANCELLED) skip`. Lightweight check, handles the race.

### 4. Provider failover during execution
If a run fails on its original provider and `ProviderHealthTracker` triggers failover, the run stays in its original provider's pool slot. The LLM call goes to a different provider via `LlmRouter`, but the pool slot is based on the _configured_ provider. This is correct — the slot reflects infrastructure load, not which LLM actually served the request.

### 5. Virtual threads
Java 21 + GCP profile already enables virtual threads. `ProviderRunPool` dispatch threads use `Thread.ofVirtual()` to avoid wasting platform threads on blocking `queue.take()` + `semaphore.acquire()`.

## Implementation Order

1. `ProviderRunPoolProperties` + YAML config
2. `ProviderQueueEntry` record
3. `ProviderRunPool` class
4. `ProviderRunPoolManager` component
5. `PipelineHarness` — inject manager, modify 5 entry points
6. `CustomMetricsConfig` — new metric constants
7. Tests: priority ordering, capacity, rejection, concurrent multi-provider

## Files to Modify

| File | Change |
|------|--------|
| `config/ProviderRunPoolProperties.java` | **NEW** |
| `harness/ProviderQueueEntry.java` | **NEW** |
| `harness/ProviderRunPool.java` | **NEW** |
| `harness/ProviderRunPoolManager.java` | **NEW** |
| `harness/PipelineHarness.java` | Modify dispatch in 5 entry points |
| `config/CustomMetricsConfig.java` | Add 3 metric constants |
| `resources/application.yml` | Add `agent.provider-pools` section |
| `harness/CLAUDE.md` | Document new pool architecture |
