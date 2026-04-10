package com.navneet.executespec.harness;

import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.state.StateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProviderRunPool")
class ProviderRunPoolTest {

    @Mock
    private StateStore stateStore;

    private ProviderRunPool pool;

    @BeforeEach
    void setUp() {
        // concurrency=2, queueCapacity=5
        pool = new ProviderRunPool(LlmProvider.ANTHROPIC, 2, 5, stateStore);
        // Default: runs are not cancelled
        when(stateStore.findRun(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        pool.shutdown();
    }

    @Test
    @DisplayName("dispatches submitted task and executes it")
    void executesSubmittedTask() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        pool.submit("run-1", "FREE", Instant.now(), done::countDown);
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("priority ordering: ENTERPRISE runs before PRO and FREE")
    void priorityOrdering() throws InterruptedException {
        // Saturate the pool with a long-running task to keep queue occupied
        CountDownLatch blockGate = new CountDownLatch(1);
        CountDownLatch slot1Taken = new CountDownLatch(1);
        CountDownLatch slot2Taken = new CountDownLatch(1);

        // Fill both concurrency slots
        pool.submit("blocker-1", "FREE", Instant.now(), () -> {
            slot1Taken.countDown();
            try { blockGate.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        pool.submit("blocker-2", "FREE", Instant.now(), () -> {
            slot2Taken.countDown();
            try { blockGate.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        // Wait for both slots to be taken
        slot1Taken.await(3, TimeUnit.SECONDS);
        slot2Taken.await(3, TimeUnit.SECONDS);

        // Now queue tasks with different priorities
        List<String> executionOrder = new ArrayList<>();
        CountDownLatch allDone = new CountDownLatch(3);

        pool.submit("free-run", "FREE", Instant.now().minusSeconds(10), () -> {
            executionOrder.add("FREE");
            allDone.countDown();
        });
        pool.submit("pro-run", "PRO", Instant.now().minusSeconds(5), () -> {
            executionOrder.add("PRO");
            allDone.countDown();
        });
        pool.submit("enterprise-run", "ENTERPRISE", Instant.now(), () -> {
            executionOrder.add("ENTERPRISE");
            allDone.countDown();
        });

        // Release slots
        blockGate.countDown();

        assertThat(allDone.await(5, TimeUnit.SECONDS)).isTrue();

        // ENTERPRISE should have run before PRO, PRO before FREE
        assertThat(executionOrder.indexOf("ENTERPRISE"))
                .isLessThan(executionOrder.indexOf("PRO"));
        assertThat(executionOrder.indexOf("PRO"))
                .isLessThan(executionOrder.indexOf("FREE"));
    }

    @Test
    @DisplayName("rejects submission when queue is full")
    void rejectsWhenQueueFull() throws InterruptedException {
        // Saturate concurrency slots first
        CountDownLatch blockGate = new CountDownLatch(1);
        pool.submit("blocker-1", "FREE", Instant.now(),
                () -> { try { blockGate.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        pool.submit("blocker-2", "FREE", Instant.now(),
                () -> { try { blockGate.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });

        Thread.sleep(100); // let blockers acquire slots

        // Fill queue to capacity (5)
        for (int i = 0; i < 5; i++) {
            pool.submit("queued-" + i, "FREE", Instant.now(), () -> {});
        }

        // Next submission should fail
        assertThatThrownBy(() -> pool.submit("overflow", "FREE", Instant.now(), () -> {}))
                .isInstanceOf(ProviderRunPool.ProviderQueueFullException.class);

        blockGate.countDown();
    }

    @Test
    @DisplayName("skips cancelled runs at dequeue")
    void skipsCancelledRuns() throws InterruptedException {
        // Mark run as cancelled
        com.navneet.executespec.state.entity.PipelineRun cancelledRun =
                mock(com.navneet.executespec.state.entity.PipelineRun.class);
        when(cancelledRun.getStatus()).thenReturn("CANCELLED");
        when(stateStore.findRun("cancelled-run")).thenReturn(Optional.of(cancelledRun));

        AtomicInteger executed = new AtomicInteger(0);
        pool.submit("cancelled-run", "FREE", Instant.now(), executed::incrementAndGet);

        Thread.sleep(500);
        assertThat(executed.get()).isZero();
    }

    @Test
    @DisplayName("activeRuns and queueDepth report correctly")
    void metricsAreAccurate() throws InterruptedException {
        CountDownLatch blockGate = new CountDownLatch(1);
        CountDownLatch slotTaken = new CountDownLatch(1);

        pool.submit("active-run", "FREE", Instant.now(), () -> {
            slotTaken.countDown();
            try { blockGate.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        slotTaken.await(3, TimeUnit.SECONDS);
        assertThat(pool.activeRuns()).isGreaterThanOrEqualTo(1);

        pool.submit("queued-run", "FREE", Instant.now(), () -> {});
        // The second slot may have picked it up — allow brief time
        Thread.sleep(50);
        // queueDepth is 0 or 1 depending on whether 2nd slot was free
        assertThat(pool.activeRuns() + pool.queueDepth()).isGreaterThanOrEqualTo(1);

        blockGate.countDown();
    }

    @Test
    @DisplayName("ProviderQueueEntry priority: ENTERPRISE=0, PRO=1, FREE=2")
    void queueEntryPriority() {
        assertThat(ProviderQueueEntry.tierToPriority("ENTERPRISE")).isEqualTo(0);
        assertThat(ProviderQueueEntry.tierToPriority("PRO")).isEqualTo(1);
        assertThat(ProviderQueueEntry.tierToPriority("FREE")).isEqualTo(2);
        assertThat(ProviderQueueEntry.tierToPriority(null)).isEqualTo(2);
        assertThat(ProviderQueueEntry.tierToPriority("UNKNOWN")).isEqualTo(2);
    }

    @Test
    @DisplayName("compareTo: same tier uses FIFO by submission time")
    void fifoWithinSameTier() {
        Instant t1 = Instant.now();
        Instant t2 = t1.plusSeconds(1);

        ProviderQueueEntry earlier = new ProviderQueueEntry("run-a", 1, t1, () -> {});
        ProviderQueueEntry later   = new ProviderQueueEntry("run-b", 1, t2, () -> {});

        assertThat(earlier.compareTo(later)).isLessThan(0);
        assertThat(later.compareTo(earlier)).isGreaterThan(0);
    }
}
