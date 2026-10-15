/* ==================================================================
 * OpRunner.java - 16/10/2026 9:00:00 am
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

package net.solarnetwork.node.system.diagnostics;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Run diagnostic operations on dedicated worker threads with a hard timeout.
 *
 * <p>
 * Each operation name gets its own lazily-created, single-thread daemon
 * executor, and at most one task per operation is in flight at any time. When a
 * task does not finish within its hard timeout it is cancelled (interrupted)
 * and the operation is marked as stuck until the task actually finishes;
 * further runs of that operation are rejected as {@link Outcome.Kind#BUSY}
 * until then.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public final class OpRunner {

	/** The worker thread name prefix. */
	public static final String THREAD_NAME_PREFIX = "Diagnostics-";

	/** The idle time after which a worker thread exits, in seconds. */
	private static final long IDLE_KEEP_ALIVE_SECS = 60;

	private final Clock clock;
	private final Function<String, ThreadPoolExecutor> executorFactory;
	private final Map<String, ThreadPoolExecutor> executors = new HashMap<>(4);
	private final Map<String, RunState> running = new HashMap<>(4);
	private boolean shutdown;

	/**
	 * The outcome of running a task.
	 */
	public static final class Outcome {

		/**
		 * The outcome kind.
		 */
		public enum Kind {

			/** The task completed successfully. */
			OK,

			/** A previous task for the same operation is still running. */
			BUSY,

			/** The task did not complete within the hard timeout. */
			TIMEOUT,

			/** The task failed. */
			ERROR,

		}

		private static final Outcome BUSY_OUTCOME = new Outcome(Kind.BUSY, null, null);
		private static final Outcome TIMEOUT_OUTCOME = new Outcome(Kind.TIMEOUT, null, null);

		private final Kind kind;
		private final @Nullable DiagnosticResult result;
		private final @Nullable Throwable error;

		private Outcome(Kind kind, @Nullable DiagnosticResult result, @Nullable Throwable error) {
			super();
			this.kind = kind;
			this.result = result;
			this.error = error;
		}

		/**
		 * Create a successful outcome.
		 *
		 * @param result
		 *        the result
		 * @return the outcome
		 */
		public static Outcome ok(DiagnosticResult result) {
			return new Outcome(Kind.OK, result, null);
		}

		/**
		 * Get a busy outcome.
		 *
		 * @return the outcome
		 */
		public static Outcome busy() {
			return BUSY_OUTCOME;
		}

		/**
		 * Get a timeout outcome.
		 *
		 * @return the outcome
		 */
		public static Outcome timeout() {
			return TIMEOUT_OUTCOME;
		}

		/**
		 * Create an error outcome.
		 *
		 * @param error
		 *        the error
		 * @return the outcome
		 */
		public static Outcome error(Throwable error) {
			return new Outcome(Kind.ERROR, null, error);
		}

		/**
		 * Get the outcome kind.
		 *
		 * @return the kind, never {@code null}
		 */
		public Kind getKind() {
			return kind;
		}

		/**
		 * Get the result.
		 *
		 * @return the result, only available for {@link Kind#OK}
		 */
		public @Nullable DiagnosticResult getResult() {
			return result;
		}

		/**
		 * Get the error.
		 *
		 * @return the error, only available for {@link Kind#ERROR}
		 */
		public @Nullable Throwable getError() {
			return error;
		}

		@Override
		public String toString() {
			return "Outcome{" + kind + "}";
		}

	}

	private static final class RunState {

		private final long sinceMs;
		private final AtomicBoolean started = new AtomicBoolean();
		private volatile boolean stuck;

		private RunState(long sinceMs) {
			super();
			this.sinceMs = sinceMs;
		}

	}

	/**
	 * Constructor.
	 *
	 * <p>
	 * The system UTC clock is used.
	 * </p>
	 */
	public OpRunner() {
		this(Clock.systemUTC());
	}

	/**
	 * Constructor.
	 *
	 * @param clock
	 *        the wall clock, used only for reporting stuck operations
	 */
	public OpRunner(Clock clock) {
		this(clock, OpRunner::newExecutor);
	}

	/**
	 * Constructor.
	 *
	 * @param clock
	 *        the wall clock, used only for reporting stuck operations
	 * @param executorFactory
	 *        a factory for the single-thread executor of an operation, given
	 *        the operation name
	 */
	public OpRunner(Clock clock, Function<String, ThreadPoolExecutor> executorFactory) {
		super();
		this.clock = clock;
		this.executorFactory = executorFactory;
	}

	/**
	 * Run a task for an operation, waiting at most {@code hardMs} for it to
	 * complete.
	 *
	 * @param op
	 *        the operation name
	 * @param task
	 *        the task to run
	 * @param hardMs
	 *        the hard timeout, in milliseconds
	 * @return the outcome, never {@code null}
	 */
	public Outcome run(String op, Callable<DiagnosticResult> task, long hardMs) {
		final RunState state;
		final Future<DiagnosticResult> future;
		synchronized ( this ) {
			if ( shutdown ) {
				return Outcome.error(new RejectedExecutionException("Shut down"));
			}
			if ( running.containsKey(op) ) {
				return Outcome.busy();
			}
			state = new RunState(clock.millis());
			final ThreadPoolExecutor executor = executors.computeIfAbsent(op, executorFactory);
			running.put(op, state);
			try {
				future = executor.submit(() -> {
					if ( !state.started.compareAndSet(false, true) ) {
						// cancelled before starting
						throw new CancellationException();
					}
					try {
						return task.call();
					} finally {
						finished(op, state);
					}
				});
			} catch ( Throwable t ) {
				// for example RejectedExecutionException, or OutOfMemoryError creating the thread
				if ( state.started.compareAndSet(false, true) ) {
					// the task will never run, so it will never clear itself
					running.remove(op, state);
				}
				if ( executors.remove(op, executor) ) {
					executor.shutdownNow();
				}
				return Outcome.error(t);
			}
		}
		try {
			return Outcome.ok(future.get(hardMs, TimeUnit.MILLISECONDS));
		} catch ( TimeoutException e ) {
			state.stuck = true;
			cancel(op, state, future);
			return Outcome.timeout();
		} catch ( ExecutionException e ) {
			final Throwable cause = e.getCause();
			return Outcome.error(cause != null ? cause : e);
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			cancel(op, state, future);
			return Outcome.error(e);
		} catch ( CancellationException e ) {
			return Outcome.error(e);
		}
	}

	private void cancel(String op, RunState state, Future<DiagnosticResult> future) {
		future.cancel(true);
		if ( state.started.compareAndSet(false, true) ) {
			// the task never started, so it will never clear itself
			finished(op, state);
		}
	}

	private synchronized void finished(String op, RunState state) {
		running.remove(op, state);
	}

	/**
	 * Get the operations that are stuck: those whose last run exceeded its hard
	 * timeout and has not yet finished.
	 *
	 * @return list of maps with {@code op} and {@code sinceMs} keys, sorted by
	 *         operation name
	 */
	public synchronized List<Map<String, Object>> stuckOps() {
		final Map<String, RunState> sorted = new TreeMap<>(running);
		final List<Map<String, Object>> result = new ArrayList<>(sorted.size());
		for ( Map.Entry<String, RunState> e : sorted.entrySet() ) {
			if ( !e.getValue().stuck ) {
				continue;
			}
			final Map<String, Object> m = new LinkedHashMap<>(2);
			m.put("op", e.getKey());
			m.put("sinceMs", e.getValue().sinceMs);
			result.add(m);
		}
		return result;
	}

	/**
	 * Test if an operation currently has a task in flight.
	 *
	 * @param op
	 *        the operation name
	 * @return {@code true} if a task for the operation is running
	 */
	public synchronized boolean isRunning(String op) {
		return running.containsKey(op);
	}

	/**
	 * Shut down all worker threads, interrupting any running tasks.
	 *
	 * <p>
	 * After calling this method all further runs fail.
	 * </p>
	 */
	public synchronized void shutdown() {
		shutdown = true;
		for ( ThreadPoolExecutor executor : executors.values() ) {
			executor.shutdownNow();
		}
		executors.clear();
	}

	/**
	 * Create the default single-thread daemon executor for an operation.
	 *
	 * @param op
	 *        the operation name
	 * @return the new executor
	 */
	public static ThreadPoolExecutor newExecutor(String op) {
		final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, IDLE_KEEP_ALIVE_SECS,
				TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
					final Thread t = new Thread(r, THREAD_NAME_PREFIX + op);
					t.setDaemon(true);
					return t;
				});
		executor.allowCoreThreadTimeOut(true);
		return executor;
	}

}
