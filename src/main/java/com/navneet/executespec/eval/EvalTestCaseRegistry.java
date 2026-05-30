package com.navneet.executespec.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;

import com.navneet.executespec.router.AgentTier;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Loads eval test cases from prompt files and constructs {@link EvalTestCase} instances with
 * validation patterns and scoring metadata.
 *
 * <p>Each tier has 3 tests designed to stress-test the model's capabilities at that cognitive
 * level. Validation patterns check for deep reasoning markers, not just surface-level JSON
 * formatting.
 */
@Slf4j
@Component
public class EvalTestCaseRegistry {

  private List<EvalTestCase> testCases;

  @PostConstruct
  void init() {
    testCases = new ArrayList<>();

    // PLANNING tier (3 tests)
    // Tests deep architectural reasoning, subtle bug detection, and
    // multi-constraint decision making.

    testCases.add(
        new EvalTestCase(
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
                Pattern.compile(
                    "(?i)(operational.?transform|OT|split.?brain|concurrent.?edit|conflict.?resolut)"),
                // Must have at least one HIGH complexity task
                Pattern.compile("\"complexity\"\\s*:\\s*\"HIGH\""),
                // Must mention version vectors or offline reconciliation
                Pattern.compile("(?i)(version.?vector|offline|reconcil|merge|branch)")),
            List.of("tasks", "project_type", "plan_title", "plan_summary"),
            EvalScorer.latencyCeilingMs(AgentTier.PLANNING),
            0));

    testCases.add(
        new EvalTestCase(
            "planning-code-review",
            AgentTier.PLANNING,
            "Review a distributed rate limiter for race conditions, TOCTOU bugs, and metric"
                + " cardinality issues",
            null,
            loadPrompt("eval/planning-code-review.txt"),
            List.of(
                Pattern.compile("\"issues\"\\s*:\\s*\\["),
                // Must catch TOCTOU in Lua script (INCR + EXPIRE non-atomic window start)
                Pattern.compile(
                    "(?i)(toctou|time.?of.?check|incr.*expire|atomic|race.?condition.*lua)"),
                // Must catch volatile HashMap thread-safety issue
                Pattern.compile(
                    "(?i)(volatile.*hashmap|concurrenthashmap|thread.?safe.*map|immutable.*copy)"),
                // Must catch high-cardinality metrics tag (client_id → OOM)
                Pattern.compile(
                    "(?i)(cardinality|client.?id.*tag|prometheus.*oom|metric.*explosion|label.*cardinality)"),
                // Must catch burst rate limiter TOCTOU (increment + expire separate)
                Pattern.compile(
                    "(?i)(burst.*race|burst.*toctou|increment.*expire.*separate|burst.*atomic)"),
                // Must have missed_by_junior field
                Pattern.compile("\"missed_by_junior\""),
                // Must rate overall risk as HIGH
                Pattern.compile("\"overall_risk\"\\s*:\\s*\"HIGH\"")),
            List.of("issues", "overall_risk", "missed_by_junior"),
            EvalScorer.latencyCeilingMs(AgentTier.PLANNING),
            0));

    testCases.add(
        new EvalTestCase(
            "planning-architecture-decision",
            AgentTier.PLANNING,
            "Make a justified architecture decision for a fintech payment system with competing"
                + " constraints",
            null,
            loadPrompt("eval/planning-architecture-decision.txt"),
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
                Pattern.compile("(?i)(multi.?currency|currency.*expansion|apac|eur|gbp)")),
            List.of("recommendation", "reasoning", "implementation_phases"),
            EvalScorer.latencyCeilingMs(AgentTier.PLANNING),
            0));

    // WORKHORSE tier (3 tests)
    // Tests multi-file code generation, complex state management,
    // and deep bug-fixing across interacting components.

    testCases.add(
        new EvalTestCase(
            "workhorse-websocket-notification",
            AgentTier.WORKHORSE,
            "Generate a complete WebSocket notification system with retry, rate limiting, and"
                + " reconnect draining",
            null,
            loadPrompt("eval/workhorse-websocket-notification.txt"),
            List.of(
                // Must have WebSocket STOMP config
                Pattern.compile(
                    "(?i)(@EnableWebSocketMessageBroker|StompEndpointRegistry|registerStompEndpoints)"),
                // Must have connection tracking with concurrent map
                Pattern.compile(
                    "(?i)(ConcurrentHashMap|concurrent.*session|session.*connect|SessionConnectEvent)"),
                // Must implement retry with exponential backoff
                Pattern.compile("(?i)(exponential.*backoff|retry.*delay|Math\\.pow|backoff.*ms)"),
                // Must have rate limiting logic
                Pattern.compile(
                    "(?i)(rate.?limit|30.*per.*minute|notification.*throttl|bucket|digest)"),
                // Must have pending notification draining on reconnect
                Pattern.compile("(?i)(pending.*notification|drain|reconnect.*deliver|undelivered)"),
                // Must use @Transactional
                Pattern.compile("@Transactional"),
                // Must define Notification entity with JPA annotations
                Pattern.compile("@Entity"),
                // Must define NotificationType enum
                Pattern.compile(
                    "(?i)(enum\\s+NotificationType|INFO.*WARNING.*ERROR|ACTION_REQUIRED)")),
            List.of(),
            EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
            0));

    testCases.add(
        new EvalTestCase(
            "workhorse-virtualized-search-list",
            AgentTier.WORKHORSE,
            "Generate a virtualized infinite-scroll list with fuzzy search, optimistic updates, and"
                + " keyboard nav",
            null,
            loadPrompt("eval/workhorse-react-component.txt"),
            List.of(
                // Must implement virtual scroll (position calculation)
                Pattern.compile(
                    "(?i)(scrollTop|offsetHeight|cumulative.*height|visible.*items|startIndex|endIndex|overscan)"),
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
                Pattern.compile("(?i)(<T\\s+extends|VirtualizedSearchListProps<T>)")),
            List.of(),
            EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
            0));

    testCases.add(
        new EvalTestCase(
            "workhorse-multi-bug-fix",
            AgentTier.WORKHORSE,
            "Fix 7 interacting bugs in a billing service (thread-safety, cache staleness, NPE,"
                + " overpayment)",
            null,
            loadPrompt("eval/workhorse-compilation-fix.txt"),
            List.of(
                // Must fix HashMap → ConcurrentHashMap
                Pattern.compile("ConcurrentHashMap"),
                // Must fix .get() → .orElse() for stream reduce
                Pattern.compile("(?i)(\\.orElse\\(|reduce\\(BigDecimal\\.ZERO)"),
                // Must fix overpayment guard (check amount > remaining)
                Pattern.compile(
                    "(?i)(overpay|exceed|cap.*amount|Math\\.min|remaining.*amount|max.*payable)"),
                // Must fix getAsDouble → orElse for empty stream
                Pattern.compile("(?i)(orElse\\(0\\.0\\)|isPresent|ifPresent|OptionalDouble)"),
                // Must add @Param to JPQL query
                Pattern.compile("@Param"),
                // Must invalidate/recompute cache correctly
                Pattern.compile("(?i)(cache.*invalidat|cache.*remove|recalculate.*balance|evict)")),
            List.of(),
            EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
            0));

    // Tests judgment and tradeoff reasoning with genuinely conflicting
    // stakeholder demands. Model must identify real tensions, not just
    // list requirements.
    testCases.add(
        new EvalTestCase(
            "planning-conflicting-requirements",
            AgentTier.PLANNING,
            "Resolve conflicting stakeholder requirements for a payment processing system",
            null,
            loadPrompt("eval/planning-conflicting-requirements.txt"),
            List.of(
                // 1. Identifies conflict: real-time WebSocket + audit logging creates latency
                // tension
                Pattern.compile(
                    "(?i)(websocket.*audit.*laten|real.?time.*audit.*conflict|logging.*adds.*(latency|I/O|overhead).*hot.*path|audit.*overhead.*laten|laten.*tension.*audit)"),
                // 2. Identifies conflict: "no payment data in logs" + "full audit trail with
                // before/after state"
                Pattern.compile(
                    "(?i)(no.*payment.*data.*log.*audit.*trail|audit.*trail.*IS.*log|audit.*contain.*payment.*data.*conflict|before.*after.*state.*vs.*no.*log|scope.*audit.*carefully|redact.*mask.*payment.*audit)"),
                // 3. Identifies conflict: sub-200ms latency + PCI DSS encryption overhead
                Pattern.compile(
                    "(?i)(200.*ms.*encrypt|laten.*encrypt|PCI.*laten|encrypt.*overhead.*laten|TLS.*laten|encrypt.*sub.?200)"),
                // 4. Architecture uses team's Python/Django skills (doesn't propose Rust/Go/Elixir)
                Pattern.compile(
                    "(?i)(python|django|team.*(experience|skill|proficien)|leverage.*existing)"),
                // 5. Proposes async audit to decouple from payment hot path
                Pattern.compile(
                    "(?i)(async.*audit|queue.*audit|decouple.*audit|audit.*queue|background.*audit|event.*driven.*audit|kafka.*audit|celery.*audit|rabbit.*audit)"),
                // 6. Includes rejected_alternatives with at least 2 entries
                Pattern.compile("\"rejected_alternatives\"\\s*:\\s*\\["),
                // 7. Negative check: does NOT ignore the 12-week timeline constraint
                Pattern.compile(
                    "(?i)(12.*week|timeline|sprint|phase|milestone|deliver.*within|schedule)")),
            List.of(
                "websocket-audit-latency-tension",
                "no-log-vs-audit-trail-conflict",
                "latency-vs-encryption-conflict",
                "uses-team-skills",
                "async-audit-decoupling",
                "rejected-alternatives-present",
                "respects-timeline-constraint"),
            List.of("conflicts", "architecture", "tradeoffs", "rejected_alternatives"),
            EvalScorer.latencyCeilingMs(AgentTier.PLANNING),
            0));

    // WORKHORSE: optimistic-locking (hard)
    // Tests correctness on a subtle concurrency problem where @Transactional
    // placement matters critically. The spec deliberately omits what happens
    // when retries are exhausted — model must infer an explicit failure path.
    testCases.add(
        new EvalTestCase(
            "workhorse-optimistic-locking",
            AgentTier.WORKHORSE,
            "Generate a banking transfer service with optimistic locking, retry, and correct"
                + " @Transactional placement",
            null,
            loadPrompt("eval/workhorse-optimistic-locking.txt"),
            List.of(
                // 1. @Version annotation on Account entity
                Pattern.compile("@Version"),
                // 2. @Transactional on the inner transfer method, NOT wrapping the retry loop
                Pattern.compile(
                    "@Transactional[^\\n"
                        + "]*\\n"
                        + "[^}]*(?:debit|transfer|doTransfer|performTransfer|executeInner)"),
                // 3. OptimisticLockException catch with retry counter
                Pattern.compile(
                    "(?i)(OptimisticLock(ing)?Exception|StaleObjectStateException|ObjectOptimisticLockingFailureException)"),
                // 4. Exponential backoff with increasing delay
                Pattern.compile(
                    "(?i)(Thread\\.sleep|TimeUnit.*sleep|backoff|100\\s*\\*|Math\\.pow|delay.*\\*.*2|exponential)"),
                // 5. Balance check + debit atomic (same transaction, read-check-update)
                Pattern.compile(
                    "(?i)(balance.*<.*amount|balance.*compareTo|insufficient.*balance|balance.*less.*than|getBalance.*subtract|balance.*>=|balance.*enough)"),
                // 6. Self-transfer rejection
                Pattern.compile(
                    "(?i)(fromId.*equals.*toId|from.*==.*to|self.?transfer|same.*account|source.*destination.*equal)"),
                // 7. Explicit failure path when retries exhausted (throws exception)
                Pattern.compile(
                    "(?i)(throw.*new.*(Exception|RuntimeException|TransferException|OptimisticLock)|retries.*exhaust|max.*retries.*exceeded|failed.*after.*retries)"),
                // 8. Negative check: @Transactional does NOT appear on the retry-loop method
                //    (Check: the outer method calling the retry loop should NOT have
                // @Transactional)
                //    We check that there's a retry loop WITHOUT @Transactional wrapping it
                Pattern.compile(
                    "(?i)(retry|attempt|while|for.*attempt)[\\s\\S]{0,200}(catch.*OptimisticLock|OptimisticLock.*catch)")),
            List.of(
                "version-annotation",
                "transactional-on-inner-method",
                "optimistic-lock-exception-catch",
                "exponential-backoff",
                "atomic-balance-check",
                "self-transfer-rejection",
                "explicit-failure-on-retry-exhaust",
                "retry-loop-without-transactional"),
            List.of(),
            EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
            0));

    // LIGHTWEIGHT tier (3 tests)
    // Tests speed and precision: ambiguity detection, multi-audience
    // summarization, and signal-vs-noise filtering.

    testCases.add(
        new EvalTestCase(
            "lightweight-request-triage",
            AgentTier.LIGHTWEIGHT,
            "Triage an ambiguous multi-part user request: identify ambiguities, prioritize by"
                + " business impact",
            null,
            loadPrompt("eval/lightweight-request-triage.txt"),
            List.of(
                Pattern.compile("\"classification\"\\s*:\\s*\"MULTI_TASK\""),
                // Must identify multiple ambiguities
                Pattern.compile("\"ambiguities\"\\s*:\\s*\\["),
                // Must flag data mismatch as highest priority
                Pattern.compile(
                    "(?i)(data.*mismatch|data.*integrity|P0.*data|highest.*priority.*data)"),
                // Must have at least 3 tasks
                Pattern.compile("\"task_id\"\\s*:\\s*\"task-3\""),
                // Must identify the "which dashboard" ambiguity
                Pattern.compile(
                    "(?i)(which.*dashboard|admin.*dashboard|customer.*dashboard|monitoring)"),
                // Must have risks_if_no_clarification
                Pattern.compile("\"risks_if_no_clarification\"")),
            List.of("classification", "ambiguities", "tasks"),
            EvalScorer.latencyCeilingMs(AgentTier.LIGHTWEIGHT),
            4000 // ~1000 tokens — triage should be concise
            ));

    testCases.add(
        new EvalTestCase(
            "lightweight-dual-summary",
            AgentTier.LIGHTWEIGHT,
            "Generate developer + stakeholder summaries from a raw pipeline report with financial"
                + " risk",
            null,
            loadPrompt("eval/lightweight-review-summary.txt"),
            List.of(
                // Must have both summaries
                Pattern.compile("\"developer_summary\""),
                Pattern.compile("\"stakeholder_summary\""),
                // Must flag as blocking release
                Pattern.compile("\"blocks_release\"\\s*:\\s*true"),
                // Must mention double refund / financial risk in one of the summaries
                Pattern.compile(
                    "(?i)(double.*refund|financial.*loss|idempotency|duplicate.*webhook)"),
                // Must mention PCI DSS / API key exposure
                Pattern.compile("(?i)(pci|api.?key.*log|credential.*exposure|compliance)"),
                // Must have severity distribution
                Pattern.compile("\"severity_distribution\""),
                // Must have top_3_actions
                Pattern.compile("\"top_3_actions\"")),
            List.of("developer_summary", "stakeholder_summary", "blocks_release"),
            EvalScorer.latencyCeilingMs(AgentTier.LIGHTWEIGHT),
            6000 // ~1500 tokens — dual summary can be slightly longer
            ));

    testCases.add(
        new EvalTestCase(
            "lightweight-config-diff-analysis",
            AgentTier.LIGHTWEIGHT,
            "Identify only breaking changes between two K8s deployment configs (6 breaking, ignore"
                + " cosmetic)",
            null,
            loadPrompt("eval/lightweight-validation-report.txt"),
            List.of(
                Pattern.compile("\"breaking_changes\"\\s*:\\s*\\["),
                // Must catch replica reduction (3 → 2)
                Pattern.compile("(?i)(replica.*3.*2|replica.*reduc)"),
                // Must catch maxUnavailable change (0 → 1)
                Pattern.compile(
                    "(?i)(maxUnavailable.*0.*1|zero.?downtime|rolling.*update.*unavailable)"),
                // Must catch STRIPE_API_KEY removal
                Pattern.compile("(?i)(stripe.*removed|stripe.*missing|stripe.*api.*key.*drop)"),
                // Must catch secret key name change (url → connection-string)
                Pattern.compile("(?i)(connection.?string|secret.*key.*changed|url.*connection)"),
                // Must catch DEBUG log level in production
                Pattern.compile("(?i)(debug.*production|log.*level.*debug|info.*debug)"),
                // Must catch topology constraint change (DoNotSchedule → ScheduleAnyway)
                Pattern.compile(
                    "(?i)(DoNotSchedule.*ScheduleAnyway|topology.*constraint|zone.*spread|anti.?affinity)"),
                // Must have verdict
                Pattern.compile("\"verdict\"\\s*:\\s*\"(BLOCK|REQUEST_CHANGES)\"")),
            List.of("breaking_changes", "verdict"),
            EvalScorer.latencyCeilingMs(AgentTier.LIGHTWEIGHT),
            4000 // ~1000 tokens — config diff analysis should be focused
            ));

    // BOSS-LEVEL: Distributed saga trilogy (PLANNING tier, very high complexity)
    // Three linked tests that stress-test distributed systems reasoning:
    //   Test 1: Failure diagnosis + compensation design (from broken code + 6 incidents)
    //   Test 2: Distributed consistency + dedup (concrete failure timeline)
    //   Test 3: Regulatory compliance + audit integrity (spot the non-atomic audit)
    // Models that merely regurgitate buzzwords will fail — patterns check for
    // specific reasoning, concrete numbers, and negative checks (anti-patterns rejected).

    // Test 1: Saga failure diagnosis + compensation design
    // Given broken code and 6 real incidents, diagnose root causes and design
    // correct compensation protocol. Don't name the problems — model must find them.
    testCases.add(
        new EvalTestCase(
            "planning-saga-diagnosis",
            AgentTier.PLANNING,
            "Diagnose 6 production incidents in a trading saga and design a correct compensation"
                + " protocol",
            null,
            loadPrompt("eval/planning-saga-diagnosis.txt"),
            List.of(
                // 1. Must identify @Transactional boundary spanning HTTP/remote calls as ROOT CAUSE
                Pattern.compile(
                    "(?i)(@Transactional.*HTTP|@Transactional.*remote|@Transactional.*external|HTTP.*call.*inside.*transact|external.*call.*within.*transact|network.*call.*transact.*boundary|transact.*scope.*remote|transact.*wrapping.*HTTP|transact.*boundar.*span.*remote|transact.*boundar.*span.*HTTP|transact.*encompass.*external|database.*transact.*cross.*service)"),

                // 2. Must identify timeout ≠ failure — query state BEFORE compensating
                Pattern.compile(
                    "(?i)(timeout.*does.*not.*(mean|impl|equat|equal).*fail|timeout.*query.*(exchange|state|status)|check.*(exchange|order).*status.*before.*compens|verify.*order.*state|timeout.*ambig.*must.*(query|check|verify)|assume.*fail.*incorrect|cannot.*assume.*fail.*from.*timeout|query.*actual.*state.*(before|prior))"),

                // 3. Must specify LIFO/reverse compensation ordering WITH reasoning
                Pattern.compile(
                    "(?i)(reverse.*order.*(because|to maintain|to avoid|to"
                        + " ensure|since)|LIFO.*compens|last.*executed.*first.*(cancel|compens|undo)|reverse.*compens.*(window|order|sequence)|compens.*reverse.*order|undo.*reverse.*sequence|reverse.*(chronolog|order).*compens)"),

                // 4. Must handle partial fills correctly — compensate ACTUAL filled qty, not
                // requested
                Pattern.compile(
                    "(?i)(actual.*fill.*quantit|compensate.*340|filled.*quantity.*not.*requested|partial.*fill.*only.*compens.*what.*was.*filled|match.*filled.*shares|340.*not.*500|actual.*shares.*filled)"),

                // 5. Settlement-aware compensation — cancel (unsettled) vs offsetting/reverse trade
                // (settled)
                Pattern.compile(
                    "(?i)(offsetting.*trade.*settled|settled.*cannot.*cancel.*must.*(offset|reverse)|cancel.*unsettled.*(offset|reverse).*settled|settlement.*status.*determines.*compens|reverse.*trade.*settled|counter.?trade.*settled|close.*position.*settled|settled.*require.*offsetting)"),

                // 6. Escalation for uncompensable states (broader terminology)
                Pattern.compile(
                    "(?i)(poison.*pill|dead.*letter|manual.*(intervention|reconcil|review|escala)|escalat.*(uncompens|human|ops|manual)|stuck.*compens.*(escala|alert)|alert.*(ops|team|human)|human.*(review|intervention|escala)|uncompens.*(escala|queue|manual|flag)|fail.*compens.*(escala|manual|alert))"),

                // 7. Must produce valid state machine with states, transitions, and guards
                Pattern.compile("(?i)(saga.?state.?machine|state.?machine|\"states\"\\s*:\\s*\\[)"),
                Pattern.compile(
                    "(?i)(\"guard\".*:|guard.*condition|\"transitions\"\\s*:\\s*\\[|transition.*from.*to)")),
            List.of("diagnosis", "compensation_protocol", "saga_state_machine"),
            60_000, // 60s ceiling — diagnosis task, not generation
            0,
            EvalTestCase.ValidationMode.REGEX_AND_LLM));

    // Test 2: Distributed consistency + dedup design
    // Concrete failure timeline with specific numbers. Model must reason about
    // T+2800ms settlement, 340-of-500 partial fill, and Kafka replay.
    testCases.add(
        new EvalTestCase(
            "planning-saga-consistency",
            AgentTier.PLANNING,
            "Design dedup and consistency protocol for a concrete multi-exchange failure timeline",
            null,
            loadPrompt("eval/planning-saga-consistency.txt"),
            List.of(
                // 1. Uses fencing tokens (epoch-based or monotonic), NOT TTL-only dedup
                Pattern.compile(
                    "(?i)(fencing.*token|epoch.*number|monotonic.*sequence|fencing.*epoch|generation.*counter|cas.*compar.*and.*swap)"),

                // 2. Lease-based lock with lease duration reasoning tied to exchange SLAs
                Pattern.compile(
                    "(?i)(lease.*based|lease.*duration.*exchange|lock.*lease.*SLA|lease.*timeout.*exceed|renew.*lease|heartbeat.*lease)"),

                // 3. Outbox pattern or CDC for saga state + event atomicity (not just "use Kafka")
                Pattern.compile(
                    "(?i)(outbox.*table|transactional.*outbox|CDC.*debezium|change.*data.*capture|polling.*publisher|outbox.*pattern)"),

                // 4. Leg A is SETTLED — must specify offsetting trade, NOT cancel
                Pattern.compile(
                    "(?i)(leg.*A.*settled.*offset|leg.*A.*offset.*trade|settled.*T\\+2800|cannot.*cancel.*leg.*A|offsetting.*500.*shares|sell.*leg.*settled|buy.*back|reverse.*position)"),

                // 5. Compensation quantity for Leg B is 340 (actual fill), NOT 500 (requested)
                Pattern.compile(
                    "(?i)(340.*shares|compensate.*340|partial.*fill.*340|actual.*filled.*340|sell.*340|only.*340)"),

                // 6. Idempotent compensation handler for Kafka replay
                Pattern.compile(
                    "(?i)(idempoten.*compensation|idempoten.*handler|deduplic.*event|event.*id.*idempoten|compens.*idempoten.*key|processed.*event.*skip)"),

                // 7. Must reject anti-patterns OR demonstrate awareness by avoiding them
                //    Accept: explicit rejection, anti-pattern listing, OR positive assertion of
                // correct approach
                Pattern.compile(
                    "(?i)(avoid.*retry.*without|reject.*2PC|reject.*XA|anti.?pattern|not.*use.*global.*transaction|avoid.*synchronous.*compens|must.*not.*assume.*transient|2PC.*not.*suitable|XA.*not.*feasible|global.*transact.*(avoid|reject|not)|do.*not.*(retry.*blind|retry.*without.*idempoten)|idempoten.*instead.*of.*retry|never.*assume.*transient)")),
            List.of("deduplication", "compensation_actions", "atomicity", "idempotent_handler"),
            45_000, // 45s ceiling — narrower scope, strong model should be fast
            0,
            EvalTestCase.ValidationMode.REGEX_AND_LLM));

    // Test 3: Regulatory compliance + audit integrity
    // Pre-supplies a broken audit implementation that writes to separate DB
    // AFTER state commit. Model must spot the non-atomic audit problem.
    testCases.add(
        new EvalTestCase(
            "planning-saga-compliance",
            AgentTier.PLANNING,
            "Review a broken audit implementation for MiFID II compliance and design correct audit"
                + " trail",
            null,
            loadPrompt("eval/planning-saga-compliance.txt"),
            List.of(
                // 1. Identifies non-atomic audit write as CORE problem (crash between commits loses
                // audit)
                Pattern.compile(
                    "(?i)(crash.*between.*commit|audit.*lost.*if.*crash|non.?atomic.*audit|audit.*after.*commit.*fail|separate.*database.*not.*atomic|two.*database.*writes.*not.*atomic)"),

                // 2. Audit atomic with state transition — same TX or outbox pattern
                Pattern.compile(
                    "(?i)(same.*transaction.*audit|outbox.*audit|audit.*record.*same.*commit|atomic.*with.*state|single.*transaction.*both|transactional.*outbox.*audit)"),

                // 3. Microsecond-precision timestamps (not millisecond) with clock source
                Pattern.compile(
                    "(?i)(microsecond|nanosecond|Instant\\.now.*nano|clock.*source.*NTP|PTP|precision.*beyond.*millisecond|System\\.nanoTime|java\\.time\\.Clock)"),

                // 4. Best-execution evidence — market data SNAPSHOT at decision time
                Pattern.compile(
                    "(?i)(market.*data.*snapshot|freeze.*market.*data|capture.*bid.*ask|record.*price.*at.*decision|best.*execution.*evidence.*market|snapshot.*at.*order.*time)"),

                // 5. Market data staleness threshold with concrete number and reasoning
                Pattern.compile(
                    "(?i)(50.*ms|100.*ms|stale.*threshold.*\\d+.*ms|freshness.*\\d+.*millisecond|tick.*rate.*staleness|exchange.*latency.*threshold)"),

                // 6. Must reject async audit OR assert synchronous/atomic audit (positive assertion
                // implies rejection)
                Pattern.compile(
                    "(?i)(not.*fire.*and.*forget|reject.*async.*audit|audit.*must.*not.*be.*async|avoid.*eventual.*consist.*audit|anti.?pattern.*async.*audit|never.*async.*audit|audit.*must.*(be.*synchron|be.*atomic|same.*transact)|synchron.*audit|atomic.*with.*state|audit.*record.*same.*commit|single.*transact.*both.*state.*audit|must.*not.*use.*(eventual|async).*for.*audit)")),
            List.of(
                "compliance_failures",
                "audit_atomicity_fix",
                "timestamp_requirements",
                "best_execution_evidence"),
            45_000, // 45s ceiling
            0,
            EvalTestCase.ValidationMode.REGEX_AND_LLM));

    // COST EVAL: WORKHORSE tier (10 real-world customer workloads)
    // These test cases represent typical code generation requests that customers
    // submit. They stress-test the cost/quality tradeoff for the WORKHORSE tier,
    // ranging from simple landing pages to complex full-stack apps.

    // Cost Eval 1: Simple landing page (low complexity)
    testCases.add(
        new EvalTestCase(
            "cost-landing-page",
            AgentTier.WORKHORSE,
            "Generate a landing page for a dog walking startup with hero, features, pricing, and"
                + " contact form",
            null,
            loadPrompt("eval/cost-landing-page.txt"),
            List.of(
                // Must have React component structure
                Pattern.compile(
                    "(?i)(export\\s+(default\\s+)?function|const\\s+\\w+\\s*[:=]\\s*\\(|React\\.FC)"),
                // Must have hero section with CTA
                Pattern.compile("(?i)(hero|Book.*Walk|Your.*Dog)"),
                // Must have pricing tiers
                Pattern.compile("(?i)(Basic|Premium|Unlimited|\\$15|\\$25|\\$199)"),
                // Must have form with validation
                Pattern.compile("(?i)(onSubmit|handleSubmit|form|validation|required)"),
                // Must use Tailwind classes
                Pattern.compile("(?i)(className.*=.*[\"'].*(?:flex|grid|p-|m-|bg-|text-))"),
                // Must have responsive design
                Pattern.compile("(?i)(md:|lg:|sm:|responsive|mobile)")),
            List.of(),
            EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
            0));

    // Cost Eval 2: Portfolio with dark theme (low-medium complexity)
    testCases.add(
        new EvalTestCase(
            "cost-portfolio-blog",
            AgentTier.WORKHORSE,
            "Generate a developer portfolio with dark theme, typing animation, project grid, and"
                + " blog section",
            null,
            loadPrompt("eval/cost-portfolio-blog.txt"),
            List.of(
                Pattern.compile(
                    "(?i)(export\\s+(default\\s+)?function|const\\s+\\w+\\s*[:=]\\s*\\(|React\\.FC)"),
                // Must have typing animation effect
                Pattern.compile("(?i)(typing|typewriter|setInterval|charAt|substring.*animate)"),
                // Must have dark theme colors
                Pattern.compile("(?i)(#0F172A|#1E293B|#3B82F6|dark|bg-slate)"),
                // Must have project grid with filtering
                Pattern.compile("(?i)(filter|project.*card|grid.*col|tech.*stack)"),
                // Must have blog with expand/collapse
                Pattern.compile("(?i)(blog|post|markdown|expand|collapse|Read.*More)"),
                // Must have contact form
                Pattern.compile("(?i)(contact|form|email|submit)")),
            List.of(),
            EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
            0));

    // Cost Eval 3: Kanban board with drag-and-drop (medium complexity)
    testCases.add(
        new EvalTestCase(
            "cost-kanban-board",
            AgentTier.WORKHORSE,
            "Generate a Kanban board task manager with drag-and-drop, multiple boards, and"
                + " localStorage persistence",
            null,
            loadPrompt("eval/cost-kanban-board.txt"),
            List.of(
                Pattern.compile(
                    "(?i)(export\\s+(default\\s+)?function|const\\s+\\w+\\s*[:=]\\s*\\(|React\\.FC)"),
                // Must implement drag and drop
                Pattern.compile(
                    "(?i)(onDragStart|onDragOver|onDrop|draggable|dragenter|dataTransfer)"),
                // Must have column management
                Pattern.compile("(?i)(To.*Do|In.*Progress|Done|column|addColumn)"),
                // Must have card with priority
                Pattern.compile("(?i)(priority|Low|Medium|High|label|color)"),
                // Must use localStorage
                Pattern.compile("(?i)(localStorage|setItem|getItem|JSON\\.parse|JSON\\.stringify)"),
                // Must have search functionality
                Pattern.compile("(?i)(search|filter|query|includes)")),
            List.of(),
            EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
            0));

    // Cost Eval 4: E-commerce store (medium-high complexity)
    testCases.add(
        new EvalTestCase(
            "cost-ecommerce-store",
            AgentTier.WORKHORSE,
            "Generate a full-stack candle store with product catalog, cart, checkout flow, and"
                + " Express API",
            null,
            loadPrompt("eval/cost-ecommerce-store.txt"),
            List.of(
                // Must have Express server setup
                Pattern.compile("(?i)(express\\(\\)|app\\.listen|app\\.get|app\\.post|router)"),
                // Must have product catalog with filtering
                Pattern.compile("(?i)(Soy|Beeswax|Pillar|Jar|category|filter|product)"),
                // Must have shopping cart logic
                Pattern.compile("(?i)(cart|addToCart|removeFromCart|quantity|subtotal)"),
                // Must have checkout flow
                Pattern.compile("(?i)(checkout|shipping|payment|order.*confirm|place.*order)"),
                // Must have API endpoints
                Pattern.compile("(?i)(/api/products|/api/cart|/api/orders)"),
                // Must have input validation
                Pattern.compile("(?i)(express-validator|body\\(|check\\(|validat)")),
            List.of(),
            EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
            0));

    // Cost Eval 5: SaaS dashboard with auth/RBAC (high complexity)
    testCases.add(
        new EvalTestCase(
            "cost-saas-dashboard",
            AgentTier.WORKHORSE,
            "Generate a project management SaaS with JWT auth, RBAC, Kanban, and team management",
            null,
            loadPrompt("eval/cost-saas-dashboard.txt"),
            List.of(
                // Must have JWT authentication
                Pattern.compile(
                    "(?i)(jwt|jsonwebtoken|sign\\(|verify\\(|accessToken|refreshToken)"),
                // Must have bcrypt password hashing
                Pattern.compile("(?i)(bcrypt|hash|salt|compareSync|hashSync)"),
                // Must have role-based access control
                Pattern.compile("(?i)(Admin|Member|Viewer|role|permission|authorize)"),
                // Must have project CRUD
                Pattern.compile("(?i)(project|task|Backlog|In.*Progress|Review|Done|kanban)"),
                // Must have team management
                Pattern.compile("(?i)(team|invite|member|assignee)"),
                // Must have protected routes / middleware
                Pattern.compile("(?i)(middleware|authenticate|protect|req\\.user|isAuth)")),
            List.of(),
            EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
            0));

    // Cost Eval 6: AI PDF summarizer (medium-high complexity)
    testCases.add(
        new EvalTestCase(
            "cost-ai-pdf-summarizer",
            AgentTier.WORKHORSE,
            "Generate a PDF upload + AI summary app with chat interface, usage tracking, and"
                + " streaming",
            null,
            loadPrompt("eval/cost-ai-pdf-summarizer.txt"),
            List.of(
                // Must have file upload handling
                Pattern.compile("(?i)(multer|upload|multipart|formData|drag.*drop|onDrop)"),
                // Must have document processing endpoints
                Pattern.compile("(?i)(/api/documents|/upload|/summarize|/chat)"),
                // Must have chat interface
                Pattern.compile("(?i)(chat|message|question|answer|bubble|conversation)"),
                // Must have usage/rate limiting
                Pattern.compile("(?i)(rate.*limit|usage|documents.*used|free.*tier|limit)"),
                // Must have progress indicator
                Pattern.compile("(?i)(progress|Processing|Ready|loading|stream|SSE)"),
                // Must have document listing
                Pattern.compile("(?i)(document.*list|history|sidebar|past.*document)")),
            List.of(),
            EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
            0));

    // Cost Eval 7: Real-time chat / Discord clone (high complexity)
    testCases.add(
        new EvalTestCase(
            "cost-realtime-chat",
            AgentTier.WORKHORSE,
            "Generate a Discord-like chat app with WebSocket, servers, channels, typing indicators,"
                + " and presence",
            null,
            loadPrompt("eval/cost-realtime-chat.txt"),
            List.of(
                // Must have WebSocket setup
                Pattern.compile(
                    "(?i)(WebSocket|ws\\.Server|wss|socket\\.on|io\\(|new\\s+WebSocket)"),
                // Must have server/channel structure
                Pattern.compile("(?i)(server.*channel|channel.*list|join.*server|create.*server)"),
                // Must have message handling
                Pattern.compile("(?i)(send.*message|message.*content|timestamp|username)"),
                // Must have typing indicator
                Pattern.compile("(?i)(typing|typing.*indicator|typing.*start|typing.*stop)"),
                // Must have presence/online status
                Pattern.compile(
                    "(?i)(online|offline|presence|status|green.*dot|connect.*disconnect)"),
                // Must have responsive layout
                Pattern.compile("(?i)(sidebar|panel|mobile|responsive|flex)")),
            List.of(),
            EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
            0));

    // Cost Eval 8: Appointment booking system (high complexity)
    testCases.add(
        new EvalTestCase(
            "cost-booking-system",
            AgentTier.WORKHORSE,
            "Generate a barbershop booking system with calendar, availability, admin panel, and"
                + " .ics export",
            null,
            loadPrompt("eval/cost-booking-system.txt"),
            List.of(
                // Must have service listing with prices
                Pattern.compile("(?i)(Haircut|Beard.*Trim|Combo|Coloring|Kids|\\$25|\\$15|\\$35)"),
                // Must have availability/calendar logic
                Pattern.compile("(?i)(available.*slot|time.*slot|calendar|9.*AM|6.*PM|15.*min)"),
                // Must prevent double booking
                Pattern.compile(
                    "(?i)(double.*book|overlap|conflict|already.*booked|slot.*available)"),
                // Must have admin panel
                Pattern.compile("(?i)(admin|dashboard|manage|appointment.*list|revenue)"),
                // Must have booking confirmation
                Pattern.compile("(?i)(confirm|reference.*number|booking.*ref|ics|iCalendar)"),
                // Must have API endpoints
                Pattern.compile(
                    "(?i)(/api/services|/api/barbers|/api/availability|/api/bookings)")),
            List.of(),
            EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
            0));

    // Cost Eval 9: REST API for recipe platform (medium complexity, API-only)
    testCases.add(
        new EvalTestCase(
            "cost-rest-api-recipes",
            AgentTier.WORKHORSE,
            "Generate a recipe sharing REST API with JWT auth, ratings, favorites, Swagger docs,"
                + " and validation",
            null,
            loadPrompt("eval/cost-rest-api-recipes.txt"),
            List.of(
                // Must have JWT auth with register/login/refresh
                Pattern.compile("(?i)(jwt|jsonwebtoken|register|login|refresh.*token)"),
                // Must have recipe CRUD
                Pattern.compile(
                    "(?i)(/api/recipes|POST.*recipe|GET.*recipe|PUT.*recipe|DELETE.*recipe)"),
                // Must have social features (favorites/ratings)
                Pattern.compile("(?i)(favorite|rating|star|1-5|toggle.*favorite)"),
                // Must have input validation
                Pattern.compile(
                    "(?i)(express-validator|body\\(|check\\(|validat|isEmail|isLength)"),
                // Must have Swagger/OpenAPI
                Pattern.compile(
                    "(?i)(swagger|openapi|swagger-jsdoc|swagger-ui|@swagger|/api-docs)"),
                // Must have rate limiting
                Pattern.compile("(?i)(rate.*limit|sliding.*window|100.*request|15.*minute)")),
            List.of(),
            EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
            0));

    // Cost Eval 10: Fitness dashboard with SVG charts (medium-high complexity)
    testCases.add(
        new EvalTestCase(
            "cost-fitness-dashboard",
            AgentTier.WORKHORSE,
            "Generate a fitness tracker with SVG charts, calendar heatmap, goals, streaks, and CSV"
                + " import/export",
            null,
            loadPrompt("eval/cost-fitness-dashboard.txt"),
            List.of(
                // Must have workout CRUD
                Pattern.compile(
                    "(?i)(workout|exercise|Running|Cycling|Swimming|duration|calories)"),
                // Must have SVG charts (not a library)
                Pattern.compile("(?i)(<svg|<rect|<polyline|<circle|viewBox|SVG)"),
                // Must have calendar heatmap
                Pattern.compile(
                    "(?i)(heatmap|heat.*map|calendar.*grid|green.*intensity|github.*style)"),
                // Must have streak tracking
                Pattern.compile("(?i)(streak|consecutive|flame|current.*streak)"),
                // Must have goals/progress
                Pattern.compile("(?i)(goal|progress.*ring|target|weekly.*goal|circular)"),
                // Must have CSV export/import
                Pattern.compile("(?i)(CSV|export|import|download|Blob|createObjectURL)")),
            List.of(),
            EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
            0));

    // Cost Eval 11: URL shortener with analytics (medium-high complexity)
    testCases.add(
        new EvalTestCase(
            "cost-url-shortener",
            AgentTier.WORKHORSE,
            "Generate a URL shortener with analytics dashboard, bulk shortening, rate limiting, and"
                + " JWT auth",
            null,
            loadPrompt("eval/cost-url-shortener.txt"),
            List.of(
                // Must have URL shortening with Base62
                Pattern.compile("(?i)(base62|alphanumeric|short.*code|encode|shorten)"),
                // Must have redirect endpoint
                Pattern.compile("(?i)(redirect|302|301|res\\.redirect|/:code|GET.*:code)"),
                // Must have analytics/click tracking
                Pattern.compile("(?i)(click|analytics|referrer|browser|device|User-Agent)"),
                // Must have JWT auth
                Pattern.compile("(?i)(jwt|jsonwebtoken|auth|token|Bearer)"),
                // Must have rate limiting
                Pattern.compile("(?i)(rate.*limit|10.*per.*hour|100.*authenticated)"),
                // Must have bulk shortening
                Pattern.compile("(?i)(bulk|multiple.*URL|array.*url|/api/urls/bulk)")),
            List.of(),
            EvalScorer.latencyCeilingMs(AgentTier.WORKHORSE),
            0));

    log.info(
        "[EvalTestCaseRegistry] Loaded {} test cases ({} PLANNING, {} WORKHORSE, {} LIGHTWEIGHT)",
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
