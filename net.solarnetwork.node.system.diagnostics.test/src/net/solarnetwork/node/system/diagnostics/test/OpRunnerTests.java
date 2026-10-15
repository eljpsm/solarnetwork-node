/* ==================================================================
 * OpRunnerTests.java - 16/10/2026 9:00:00 am
 *
 * Copyright 2026 SolarNetwork.net Dev Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA
 * 02111-1307 USA
 * ==================================================================
 */

package net.solarnetwork.node.system.diagnostics.test;

import static org.assertj.core.api.BDDAssertions.then;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import net.solarnetwork.node.system.diagnostics.DiagnosticResult;
import net.solarnetwork.node.system.diagnostics.OpRunner;
import net.solarnetwork.node.system.diagnostics.OpRunner.Outcome;

/**
 * Test cases for the {@link OpRunner} class.
 *
 * @author elijah
 * @version 1.0
 */
public class OpRunnerTests {

	private static final Instant NOW = Instant.parse("2026-10-16T01:02:03Z");

	private OpRunner runner;
	private CountDownLatch release;

	@Before
	public void setup() {
		runner = new OpRunner(Clock.fixed(NOW, ZoneOffset.UTC));
		release = new CountDownLatch(1);
	}

	@After
	public void teardown() {
		release.countDown();
		runner.shutdown();
	}

	/**
	 * Run a task that ignores interruption until released.
	 */
	private DiagnosticResult stubborn() {
		while ( true ) {
			try {
				if ( release.await(5, TimeUnit.SECONDS) ) {
					return new DiagnosticResult().put("released", true);
				}
			} catch ( InterruptedException e ) {
				// ignore, to simulate a task stuck in uninterruptible I/O
			}
		}
	}

	private void awaitNotRunning(String op) throws InterruptedException {
		for ( int i = 0; i < 200 && runner.isRunning(op); i++ ) {
			Thread.sleep(10);
		}
	}

	@Test
	public void ok() {
		// GIVEN
		AtomicReference<String> threadName = new AtomicReference<>();

		// WHEN
		Outcome outcome = runner.run("system", () -> {
			threadName.set(Thread.currentThread().getName());
			return new DiagnosticResult().put("a", 1);
		}, 1000);

		// THEN
		then(outcome.getKind()).isEqualTo(Outcome.Kind.OK);
		then(outcome.getResult()).isNotNull();
		then(outcome.getResult().getData()).containsEntry("a", 1);
		then(outcome.getError()).isNull();
		then(threadName.get()).as("Runs on dedicated named thread").isEqualTo("Diagnostics-system");
		then(runner.stuckOps()).isEmpty();
	}

	@Test
	public void error() {
		// WHEN
		Outcome outcome = runner.run("system", () -> {
			throw new IllegalStateException("boom");
		}, 1000);

		// THEN
		then(outcome.getKind()).isEqualTo(Outcome.Kind.ERROR);
		then(outcome.getError()).isInstanceOf(IllegalStateException.class);
		then(outcome.getResult()).isNull();
		then(runner.isRunning("system")).isFalse();
	}

	@Test
	public void error_throwable() {
		// WHEN
		Outcome outcome = runner.run("system", () -> {
			throw new OutOfMemoryError("test");
		}, 1000);

		// THEN
		then(outcome.getKind()).isEqualTo(Outcome.Kind.ERROR);
		then(outcome.getError()).isInstanceOf(OutOfMemoryError.class);

		// and the op can run again
		then(runner.run("system", DiagnosticResult::new, 1000).getKind()).isEqualTo(Outcome.Kind.OK);
	}

	@Test
	public void submitError_notLockedOut() throws Exception {
		// GIVEN
		final AtomicInteger created = new AtomicInteger();
		runner.shutdown();
		runner = new OpRunner(Clock.fixed(NOW, ZoneOffset.UTC), op -> {
			if ( created.incrementAndGet() > 1 ) {
				return OpRunner.newExecutor(op);
			}
			ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS,
					new LinkedBlockingQueue<>(), r -> {
						throw new OutOfMemoryError("unable to create native thread");
					});
			return executor;
		});

		// WHEN
		Outcome first = runner.run("system", () -> new DiagnosticResult().put("a", 1), 1000);
		boolean runningAfterFailure = runner.isRunning("system");
		Outcome second = runner.run("system", () -> new DiagnosticResult().put("a", 2), 1000);

		// THEN
		then(first.getKind()).as("Thread creation failure is an error").isEqualTo(Outcome.Kind.ERROR);
		then(first.getError()).isInstanceOf(OutOfMemoryError.class);
		then(runningAfterFailure).as("Op not left running").isFalse();
		then(runner.stuckOps()).isEmpty();
		then(second.getKind()).as("Fresh executor used next time").isEqualTo(Outcome.Kind.OK);
		then(second.getResult().getData()).containsEntry("a", 2);
		then(created).as("Failed executor replaced").hasValue(2);
	}

	@Test
	public void timeout_thenBusy_thenClears() throws Exception {
		// WHEN
		long start = System.nanoTime();
		Outcome outcome = runner.run("logs", this::stubborn, 200);
		long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

		// THEN
		then(outcome.getKind()).isEqualTo(Outcome.Kind.TIMEOUT);
		then(tookMs).as("Returns within hard deadline + 200ms").isBetween(190L, 400L);

		// @formatter:off
		List<Map<String, Object>> stuck = runner.stuckOps();
		then(stuck)
			.as("Op reported as stuck")
			.hasSize(1)
			.element(0)
			.satisfies(m -> {
				then(m).containsEntry("op", "logs").containsEntry("sinceMs", NOW.toEpochMilli());
			})
			;
		// @formatter:on

		// WHEN
		start = System.nanoTime();
		Outcome busy = runner.run("logs", () -> new DiagnosticResult(), 1000);
		long busyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		Outcome other = runner.run("system", () -> new DiagnosticResult(), 1000);

		// THEN
		then(busy.getKind()).as("Same op busy while stuck").isEqualTo(Outcome.Kind.BUSY);
		then(busyMs).as("Busy returns immediately").isLessThan(100L);
		then(other.getKind()).as("Other op still works").isEqualTo(Outcome.Kind.OK);

		// WHEN
		release.countDown();
		awaitNotRunning("logs");

		// THEN
		then(runner.stuckOps()).as("Stuck cleared after task finishes").isEmpty();
		then(runner.run("logs", () -> new DiagnosticResult(), 1000).getKind())
				.isEqualTo(Outcome.Kind.OK);
	}

	@Test
	public void timeout_interruptible() throws Exception {
		// WHEN
		Outcome outcome = runner.run("storage", () -> {
			Thread.sleep(10_000);
			return new DiagnosticResult();
		}, 200);
		awaitNotRunning("storage");

		// THEN
		then(outcome.getKind()).isEqualTo(Outcome.Kind.TIMEOUT);
		then(runner.isRunning("storage")).as("Interrupted task clears promptly").isFalse();
		then(runner.stuckOps()).isEmpty();
	}

	@Test
	public void interruptedCaller() {
		// GIVEN
		Thread.currentThread().interrupt();

		// WHEN
		Outcome outcome;
		try {
			outcome = runner.run("system", () -> new DiagnosticResult(), 1000);
		} finally {
			// THEN
			then(Thread.interrupted()).as("Interrupt flag restored").isTrue();
		}
		then(outcome.getKind()).isIn(Outcome.Kind.ERROR, Outcome.Kind.OK);
	}

	@Test
	public void shutdown() throws Exception {
		// GIVEN
		CountDownLatch started = new CountDownLatch(1);
		AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
		Thread caller = new Thread(() -> runner.run("logs", () -> {
			started.countDown();
			try {
				Thread.sleep(10_000);
			} catch ( InterruptedException e ) {
				interrupted.set(true);
			}
			return new DiagnosticResult();
		}, 10_000));
		caller.start();
		then(started.await(2, TimeUnit.SECONDS)).isTrue();

		// WHEN
		runner.shutdown();
		caller.join(2000);
		Outcome after = runner.run("system", () -> new DiagnosticResult(), 1000);

		// THEN
		then(caller.isAlive()).isFalse();
		then(interrupted.get()).as("Running task interrupted").isTrue();
		then(after.getKind()).isEqualTo(Outcome.Kind.ERROR);
		then(after.getError()).isInstanceOf(RejectedExecutionException.class);
	}

}
