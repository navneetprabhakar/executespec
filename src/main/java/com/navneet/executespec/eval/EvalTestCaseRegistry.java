package com.navneet.executespec.eval;

import com.navneet.executespec.router.AgentTier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Loads eval test cases from prompt files and constructs {@link EvalTestCase} instances
 * with validation patterns and scoring metadata.
 *
 * <p>Each tier has 3 tests designed to stress-test the model's capabilities at
 * that cognitive level. Validation patterns check for deep reasoning markers,
 * not just surface-level JSON formatting.
 */
@Slf4j
@Component
public class EvalTestCaseRegistry {

    private List<EvalTestCase> testCases;

    @PostConstruct
    void init() {
        testCases = new ArrayList<>();

        // ── PLANNING tier (3 tests) ──────────────────────────────────────────
        // Tests deep architectural reasoning, subtle bug detection, and
        // multi-constraint decision making.

        testCases.add(new EvalTestCase(
                "planning-plan-generation",
                AgentTier.PLANNING,
                "Generate an execution plan for a real-time collaborative editor with OT engine",
                null,
                loadPrompt("eval/planning-plan-generation.txt"),
                List.of(
                        Pattern.compile("\"tasks\"\\s*:\\s*\\["),
                        Pattern.compile("\"project_type\""),
                        Pattern.compile("\"depends_on\"\\s*:\\s*\\["),
                        Pattern.compile("\"parallelizable_with\""),
                        // Must have 6+ tasks
                        Pattern.compile("\"task_id\"\\s*:\\s*\"task-[6-9]|task-1[0-9]\""),
                        // Must mention OT/operational transform edge cases
                        Pattern.compile("(?i)(operational.?transform|OT|split.?brain|concurrent.?edit|conflict.?resolut)"),
                        // Must have at least one HIGH complexity task
                        Pattern.compile("\"complexity\"\\s*:\\s*\"HIGH\""),
                        // Must mention version vectors or offline reconciliation
                        Pattern.compile("(?i)(version.?vector|offline|reconcil|merge|branch)")
                ),
                List.of("tasks", "project_type", "plan_title", "plan_summary"),
                EvalScorer.latencyCeilingMs(AgentTier.PLANNING),
                0
        ));

        testCases.add(new EvalTestCase(
                "planning-code-review",
                AgentTier.PLANNING,
                "Review a distributed rate limiter for race conditions, TOCTOU bugs, and metric cardinality issues",
                null,
                loadPrompt("eval/planning-code-review.txt"),
                List.of(
                        Pattern.compile("\"issues\"\\s*:\\s*\\["),
                        // Must catch TOCTOU in Lua script (INCR + EXPIRE non-atomic window start)
                        Pattern.compile("(?i)(toctou|time.?of.?check|incr.*expire|atomic|race.?condition.*lua)"),
                        // Must catch volatile HashMap thread-safety issue
                        Pattern.compile("(?i)(volatile.*hashmap|concurrenthashmap|thread.?safe.*map|immutable.*copy)"),
                        // Must catch high-cardinality metrics tag (client_id → OOM)
                        Pattern.compile("(?i)(cardinality|client.?id.*tag|prometheus.*oom|metric.*explosion|label.*cardinality)"),
                        // Must catch burst rate limiter TOCTOU (increment + expire separate)
                        Pattern.compile("(?i)(burst.*race|burst.*toctou|increment.*expire.*separate|burst.*atomic)"),
                        // Must have missed_by_junior field
                        Pattern.compile("\"missed_by_junior\""),
                        // Must rate overall risk as HIGH
                        Pattern.compile("\"overall_risk\"\\s*:\\s*\"HIGH\"")
                ),
                List.of("issues", "overall_risk", "missed_by_junior"),
                EvalScorer.latencyCeilingMs(AgentTier.PLANNING),
                0
        ));

        testCases.add(new EvalTestCase(
                "planning-architecture-decision",
                AgentTier.PLANNING,
                "Make a justified architecture decision for a fintech payment system with competing constraints",
                null,
                loadPrompt("eval/planning-complexity-classification.txt"),
                List.of(
                        Pattern.compile("\"recommendation\"\\s*:\\s*\"[ABC]\""),
                        // Must address PCI DSS for rejected options
                        Pattern.compile("(?i)(pci.?dss|pci.*compliance|payment.?card.?industry)"),
                        // Must have implementation phases
                        Pattern.compile("\"implementation_phases\"\\s*:\\s*\\["),
                        // Must discuss team skills constraint (Python/Django team)
                        Pattern.compile("(?i)(python|django|team.*experience|skill.*gap|learning.*curve)"),
                        // Must address idempotency requirement
                        Pattern.compile("(?i)(idempoten|duplicate.*request|same.*result)"),
                        // Must have rejected_options with technical reasons
                        Pattern.compile("\"rejected_options\""),
                        // Must discuss decision reversibility
                        Pattern.compile("\"decision_reversibility\""),
                        // Must address multi-currency
                        Pattern.compile("(?i)(multi.?currency|currency.*expansion|apac|eur|gbp)")
                ),
                List.of("recommendation", "reasoning", "implementation_phases"),
                EvalScorer.latencyCeilingMs(AgentTier.PLANNING),
                0
        ));

        // ── WORKHORSE tier (3 tests) ─────────────────────────────────────────
        // Tests multi-file code generation, complex state management,
        // and deep bug-fixing across interacting components.

        testCases.add(new EvalTestCase(
                "workhorse-websocket-notification",
                AgentTier.WORKHORSE,
                "Generate a complete WebSocket notification system with retry, rate limiting, and reconnect draining",
                null,
                loadPrompt("eval/workhorse-rest-controller.txt"),
                List.of(
                        // Must have WebSocket STOMP config
                        Pattern.compile("(?i)(@EnableWebSocketMessageBroker|StompEndpointRegistry|registerStompEndpoints)"),
                        // Must have connection tracking with concurrent map
                        Pattern.compile("(?i)(ConcurrentHashMap|concurrent.*session|session.*connect|SessionConnectEvent)"),
                        // Must implement retry with exponential backoff
                        Pattern.compile("(?i)(exponential.*backoff|retry.*delay|Math\\.pow|backoff.*ms)"),
                        // Must have rate limiting logic
                        Pattern.compile("(?i)(rate.?limit|30.*per.*minute|notification.*throttl|bucket|digest)"),
                        // Must have pending notification draining on reconnect
                        Pattern.compile("(?i)(pending.*notification|drain|reconnect.*deliver|undelivered)"),
                        // Must use @Transactional
                        Pattern.compile("@Transactional"),
                        // Must define Notification entity with JPA annotations
                        Pattern.compile("@Entity"),
                        // Must define NotificationType enum
                        Pattern.compile("(?i)(enum\\s+NotificationType|INFO.*WARNING.*ERROR|ACTION_REQUIRED)")
                ),
                List.of(),
                EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
                0
        ));

        testCases.add(new EvalTestCase(
                "workhorse-virtualized-search-list",
                AgentTier.WORKHORSE,
                "Generate a virtualized infinite-scroll list with fuzzy search, optimistic updates, and keyboard nav",
                null,
                loadPrompt("eval/workhorse-react-component.txt"),
                List.of(
                        // Must implement virtual scroll (position calculation)
                        Pattern.compile("(?i)(scrollTop|offsetHeight|cumulative.*height|visible.*items|startIndex|endIndex|overscan)"),
                        // Must implement debounced search
                        Pattern.compile("(?i)(debounce|setTimeout.*search|300.*ms|clearTimeout)"),
                        // Must implement fuzzy matching
                        Pattern.compile("(?i)(fuzzy|char.*index|match.*character|indexOf.*search)"),
                        // Must implement optimistic update with rollback
                        Pattern.compile("(?i)(optimistic|revert|rollback|previous.*state|catch.*set)"),
                        // Must implement keyboard navigation
                        Pattern.compile("(?i)(ArrowDown|ArrowUp|keydown|keyboard|focusIndex|activeIndex)"),
                        // Must have AbortController for cleanup
                        Pattern.compile("(?i)(AbortController|abort.*signal|cleanup|useEffect.*return)"),
                        // Must use TypeScript generics
                        Pattern.compile("(?i)(<T\\s+extends|VirtualizedSearchListProps<T>)")
                ),
                List.of(),
                EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
                0
        ));

        testCases.add(new EvalTestCase(
                "workhorse-multi-bug-fix",
                AgentTier.WORKHORSE,
                "Fix 7 interacting bugs in a billing service (thread-safety, cache staleness, NPE, overpayment)",
                null,
                loadPrompt("eval/workhorse-compilation-fix.txt"),
                List.of(
                        // Must fix HashMap → ConcurrentHashMap
                        Pattern.compile("ConcurrentHashMap"),
                        // Must fix .get() → .orElse() for stream reduce
                        Pattern.compile("(?i)(\\.orElse\\(|reduce\\(BigDecimal\\.ZERO)"),
                        // Must fix overpayment guard (check amount > remaining)
                        Pattern.compile("(?i)(overpay|exceed|cap.*amount|Math\\.min|remaining.*amount|max.*payable)"),
                        // Must fix getAsDouble → orElse for empty stream
                        Pattern.compile("(?i)(orElse\\(0\\.0\\)|isPresent|ifPresent|OptionalDouble)"),
                        // Must add @Param to JPQL query
                        Pattern.compile("@Param"),
                        // Must invalidate/recompute cache correctly
                        Pattern.compile("(?i)(cache.*invalidat|cache.*remove|recalculate.*balance|evict)")
                ),
                List.of(),
                EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
                0
        ));

        // ── LIGHTWEIGHT tier (3 tests) ───────────────────────────────────────
        // Tests speed and precision: ambiguity detection, multi-audience
        // summarization, and signal-vs-noise filtering.

        testCases.add(new EvalTestCase(
                "lightweight-request-triage",
                AgentTier.LIGHTWEIGHT,
                "Triage an ambiguous multi-part user request: identify ambiguities, prioritize by business impact",
                null,
                loadPrompt("eval/lightweight-request-triage.txt"),
                List.of(
                        Pattern.compile("\"classification\"\\s*:\\s*\"MULTI_TASK\""),
                        // Must identify multiple ambiguities
                        Pattern.compile("\"ambiguities\"\\s*:\\s*\\["),
                        // Must flag data mismatch as highest priority
                        Pattern.compile("(?i)(data.*mismatch|data.*integrity|P0.*data|highest.*priority.*data)"),
                        // Must have at least 3 tasks
                        Pattern.compile("\"task_id\"\\s*:\\s*\"task-3\""),
                        // Must identify the "which dashboard" ambiguity
                        Pattern.compile("(?i)(which.*dashboard|admin.*dashboard|customer.*dashboard|monitoring)"),
                        // Must have risks_if_no_clarification
                        Pattern.compile("\"risks_if_no_clarification\"")
                ),
                List.of("classification", "ambiguities", "tasks"),
                EvalScorer.latencyCeilingMs(AgentTier.LIGHTWEIGHT),
                0
        ));

        testCases.add(new EvalTestCase(
                "lightweight-dual-summary",
                AgentTier.LIGHTWEIGHT,
                "Generate developer + stakeholder summaries from a raw pipeline report with financial risk",
                null,
                loadPrompt("eval/lightweight-review-summary.txt"),
                List.of(
                        // Must have both summaries
                        Pattern.compile("\"developer_summary\""),
                        Pattern.compile("\"stakeholder_summary\""),
                        // Must flag as blocking release
                        Pattern.compile("\"blocks_release\"\\s*:\\s*true"),
                        // Must mention double refund / financial risk in one of the summaries
                        Pattern.compile("(?i)(double.*refund|financial.*loss|idempotency|duplicate.*webhook)"),
                        // Must mention PCI DSS / API key exposure
                        Pattern.compile("(?i)(pci|api.?key.*log|credential.*exposure|compliance)"),
                        // Must have severity distribution
                        Pattern.compile("\"severity_distribution\""),
                        // Must have top_3_actions
                        Pattern.compile("\"top_3_actions\"")
                ),
                List.of("developer_summary", "stakeholder_summary", "blocks_release"),
                EvalScorer.latencyCeilingMs(AgentTier.LIGHTWEIGHT),
                0
        ));

        testCases.add(new EvalTestCase(
                "lightweight-config-diff-analysis",
                AgentTier.LIGHTWEIGHT,
                "Identify only breaking changes between two K8s deployment configs (6 breaking, ignore cosmetic)",
                null,
                loadPrompt("eval/lightweight-validation-report.txt"),
                List.of(
                        Pattern.compile("\"breaking_changes\"\\s*:\\s*\\["),
                        // Must catch replica reduction (3 → 2)
                        Pattern.compile("(?i)(replica.*3.*2|replica.*reduc)"),
                        // Must catch maxUnavailable change (0 → 1)
                        Pattern.compile("(?i)(maxUnavailable.*0.*1|zero.?downtime|rolling.*update.*unavailable)"),
                        // Must catch STRIPE_API_KEY removal
                        Pattern.compile("(?i)(stripe.*removed|stripe.*missing|stripe.*api.*key.*drop)"),
                        // Must catch secret key name change (url → connection-string)
                        Pattern.compile("(?i)(connection.?string|secret.*key.*changed|url.*connection)"),
                        // Must catch DEBUG log level in production
                        Pattern.compile("(?i)(debug.*production|log.*level.*debug|info.*debug)"),
                        // Must catch topology constraint change (DoNotSchedule → ScheduleAnyway)
                        Pattern.compile("(?i)(DoNotSchedule.*ScheduleAnyway|topology.*constraint|zone.*spread|anti.?affinity)"),
                        // Must have verdict
                        Pattern.compile("\"verdict\"\\s*:\\s*\"(BLOCK|REQUEST_CHANGES)\"")
                ),
                List.of("breaking_changes", "verdict"),
                EvalScorer.latencyCeilingMs(AgentTier.LIGHTWEIGHT),
                0
        ));

        // ── BOSS-LEVEL: Distributed saga trilogy (PLANNING tier, very high complexity) ──
        // Three linked tests that stress-test distributed systems reasoning:
        //   Test 1: Failure diagnosis + compensation design (from broken code + 6 incidents)
        //   Test 2: Distributed consistency + dedup (concrete failure timeline)
        //   Test 3: Regulatory compliance + audit integrity (spot the non-atomic audit)
        // Models that merely regurgitate buzzwords will fail — patterns check for
        // specific reasoning, concrete numbers, and negative checks (anti-patterns rejected).

        // ── Test 1: Saga failure diagnosis + compensation design ─────────────
        // Given broken code and 6 real incidents, diagnose root causes and design
        // correct compensation protocol. Don't name the problems — model must find them.
        testCases.add(new EvalTestCase(
                "planning-saga-diagnosis",
                AgentTier.PLANNING,
                "Diagnose 6 production incidents in a trading saga and design a correct compensation protocol",
                null,
                loadPrompt("eval/planning-saga-diagnosis.txt"),
                List.of(
                        // 1. Must identify @Transactional boundary spanning HTTP calls as ROOT CAUSE
                        //    (not just "transaction issue" — must call out the specific boundary violation)
                        Pattern.compile("(?i)(@Transactional.*HTTP|HTTP.*call.*inside.*transact|external.*call.*within.*transact|network.*call.*transact.*boundary|transact.*scope.*remote|transact.*wrapping.*HTTP)"),

                        // 2. Must identify timeout ≠ failure — query exchange state BEFORE compensating
                        Pattern.compile("(?i)(timeout.*does.*not.*mean.*fail|timeout.*query.*exchange|check.*exchange.*status.*before.*compens|verify.*order.*state|timeout.*ambig.*must.*query|assume.*fail.*incorrect)"),

                        // 3. Must specify LIFO compensation ordering WITH reasoning (not just mentioning it)
                        Pattern.compile("(?i)(reverse.*order.*because|LIFO.*compens.*settlement|last.*executed.*first.*cancel.*because|reverse.*compens.*window|compens.*reverse.*order.*settlement.*differ)"),

                        // 4. Must handle partial fills correctly — compensate ACTUAL filled qty, not requested
                        Pattern.compile("(?i)(actual.*fill.*quantit|compensate.*340|filled.*quantity.*not.*requested|partial.*fill.*only.*compens.*what.*was.*filled|match.*filled.*shares)"),

                        // 5. Settlement-aware compensation — cancel (unsettled) vs offsetting trade (settled)
                        Pattern.compile("(?i)(offsetting.*trade.*settled|settled.*cannot.*cancel.*must.*offset|cancel.*unsettled.*offset.*settled|settlement.*status.*determines.*compens)"),

                        // 6. Poison pill / dead letter escalation for uncompensable states
                        Pattern.compile("(?i)(poison.*pill|dead.*letter|manual.*intervention|escalat.*uncompens|stuck.*compens.*escala|alert.*ops.*team|human.*review)"),

                        // 7. Must produce valid state machine with states, transitions, and guards
                        Pattern.compile("\"saga_state_machine\""),
                        Pattern.compile("(?i)(\"guard\".*:|guard.*condition|\"transitions\"\\s*:\\s*\\[)")
                ),
                List.of("diagnosis", "compensation_protocol", "saga_state_machine"),
                60_000, // 60s ceiling — diagnosis task, not generation
                0
        ));

        // ── Test 2: Distributed consistency + dedup design ──────────────────
        // Concrete failure timeline with specific numbers. Model must reason about
        // T+2800ms settlement, 340-of-500 partial fill, and Kafka replay.
        testCases.add(new EvalTestCase(
                "planning-saga-consistency",
                AgentTier.PLANNING,
                "Design dedup and consistency protocol for a concrete multi-exchange failure timeline",
                null,
                loadPrompt("eval/planning-saga-consistency.txt"),
                List.of(
                        // 1. Uses fencing tokens (epoch-based or monotonic), NOT TTL-only dedup
                        Pattern.compile("(?i)(fencing.*token|epoch.*number|monotonic.*sequence|fencing.*epoch|generation.*counter|cas.*compar.*and.*swap)"),

                        // 2. Lease-based lock with lease duration reasoning tied to exchange SLAs
                        Pattern.compile("(?i)(lease.*based|lease.*duration.*exchange|lock.*lease.*SLA|lease.*timeout.*exceed|renew.*lease|heartbeat.*lease)"),

                        // 3. Outbox pattern or CDC for saga state + event atomicity (not just "use Kafka")
                        Pattern.compile("(?i)(outbox.*table|transactional.*outbox|CDC.*debezium|change.*data.*capture|polling.*publisher|outbox.*pattern)"),

                        // 4. Leg A is SETTLED — must specify offsetting trade, NOT cancel
                        Pattern.compile("(?i)(leg.*A.*settled.*offset|leg.*A.*offset.*trade|settled.*T\\+2800|cannot.*cancel.*leg.*A|offsetting.*500.*shares|sell.*leg.*settled|buy.*back|reverse.*position)"),

                        // 5. Compensation quantity for Leg B is 340 (actual fill), NOT 500 (requested)
                        Pattern.compile("(?i)(340.*shares|compensate.*340|partial.*fill.*340|actual.*filled.*340|sell.*340|only.*340)"),

                        // 6. Idempotent compensation handler for Kafka replay
                        Pattern.compile("(?i)(idempoten.*compensation|idempoten.*handler|deduplic.*event|event.*id.*idempoten|compens.*idempoten.*key|processed.*event.*skip)"),

                        // 7. NEGATIVE CHECK: must NOT propose these anti-patterns
                        //    (query exchange + compensate in same TX, "just retry", 2PC/XA)
                        //    We check this by looking for explicit rejection
                        Pattern.compile("(?i)(avoid.*retry.*without|reject.*2PC|reject.*XA|anti.?pattern|not.*use.*global.*transaction|avoid.*synchronous.*compens|must.*not.*assume.*transient)")
                ),
                List.of("deduplication", "compensation_actions", "atomicity", "idempotent_handler"),
                45_000, // 45s ceiling — narrower scope, strong model should be fast
                0
        ));

        // ── Test 3: Regulatory compliance + audit integrity ─────────────────
        // Pre-supplies a broken audit implementation that writes to separate DB
        // AFTER state commit. Model must spot the non-atomic audit problem.
        testCases.add(new EvalTestCase(
                "planning-saga-compliance",
                AgentTier.PLANNING,
                "Review a broken audit implementation for MiFID II compliance and design correct audit trail",
                null,
                loadPrompt("eval/planning-saga-compliance.txt"),
                List.of(
                        // 1. Identifies non-atomic audit write as CORE problem (crash between commits loses audit)
                        Pattern.compile("(?i)(crash.*between.*commit|audit.*lost.*if.*crash|non.?atomic.*audit|audit.*after.*commit.*fail|separate.*database.*not.*atomic|two.*database.*writes.*not.*atomic)"),

                        // 2. Audit atomic with state transition — same TX or outbox pattern
                        Pattern.compile("(?i)(same.*transaction.*audit|outbox.*audit|audit.*record.*same.*commit|atomic.*with.*state|single.*transaction.*both|transactional.*outbox.*audit)"),

                        // 3. Microsecond-precision timestamps (not millisecond) with clock source
                        Pattern.compile("(?i)(microsecond|nanosecond|Instant\\.now.*nano|clock.*source.*NTP|PTP|precision.*beyond.*millisecond|System\\.nanoTime|java\\.time\\.Clock)"),

                        // 4. Best-execution evidence — market data SNAPSHOT at decision time
                        Pattern.compile("(?i)(market.*data.*snapshot|freeze.*market.*data|capture.*bid.*ask|record.*price.*at.*decision|best.*execution.*evidence.*market|snapshot.*at.*order.*time)"),

                        // 5. Market data staleness threshold with concrete number and reasoning
                        Pattern.compile("(?i)(50.*ms|100.*ms|stale.*threshold.*\\d+.*ms|freshness.*\\d+.*millisecond|tick.*rate.*staleness|exchange.*latency.*threshold)"),

                        // 6. NEGATIVE CHECK: must NOT propose async/fire-and-forget audit writes
                        Pattern.compile("(?i)(not.*fire.*and.*forget|reject.*async.*audit|audit.*must.*not.*be.*async|avoid.*eventual.*consist.*audit|anti.?pattern.*async.*audit|never.*asynchronous.*audit)")
                ),
                List.of("compliance_failures", "audit_atomicity_fix", "timestamp_requirements", "best_execution_evidence"),
                45_000, // 45s ceiling
                0
        ));

        log.info("[EvalTestCaseRegistry] Loaded {} test cases ({} PLANNING, {} WORKHORSE, {} LIGHTWEIGHT)",
                testCases.size(),
                testCases.stream().filter(t -> t.tier() == AgentTier.PLANNING).count(),
                testCases.stream().filter(t -> t.tier() == AgentTier.WORKHORSE).count(),
                testCases.stream().filter(t -> t.tier() == AgentTier.LIGHTWEIGHT).count());
    }

    public List<EvalTestCase> allTestCases() {
        return Collections.unmodifiableList(testCases);
    }

    public List<EvalTestCase> testCasesForTier(AgentTier tier) {
        return testCases.stream().filter(t -> t.tier() == tier).toList();
    }

    private String loadPrompt(String path) {
        try {
            return new ClassPathResource("prompts/" + path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load eval prompt: " + path, e);
        }
    }
}
