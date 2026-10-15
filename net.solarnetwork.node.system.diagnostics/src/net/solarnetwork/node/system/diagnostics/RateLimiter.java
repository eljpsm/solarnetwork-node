/* ==================================================================
 * RateLimiter.java - 16/10/2026 9:00:00 am
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

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.node.system.diagnostics.DiagnosticOp.RateClass;

/**
 * Rate limiter for diagnostic operations.
 *
 * <p>
 * Each {@link RateClass} has its own token bucket with continuous refill. In
 * addition, the {@link RateClass#LIGHT} and {@link RateClass#SCAN} classes
 * share a wall-time budget: a leaky bucket that allows at most
 * {@link #DEFAULT_WALL_BUDGET_NANOS} of operation wall time per rolling
 * {@link #DEFAULT_WALL_WINDOW_NANOS}.
 * </p>
 *
 * <p>
 * All time calculations use a monotonic nanosecond time source, so wall clock
 * changes have no effect.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public final class RateLimiter {

	/** The default {@link RateClass#LIGHT} rate per hour. */
	public static final int DEFAULT_LIGHT_PER_HOUR = 60;

	/** The {@link RateClass#LIGHT} burst size. */
	public static final int LIGHT_BURST = 5;

	/** The default {@link RateClass#SCAN} rate per hour. */
	public static final int DEFAULT_SCAN_PER_HOUR = 20;

	/** The {@link RateClass#SCAN} burst size. */
	public static final int SCAN_BURST = 2;

	/** The default {@link RateClass#NONE} rate per hour. */
	public static final int DEFAULT_NONE_PER_HOUR = 120;

	/** The {@link RateClass#NONE} burst size. */
	public static final int NONE_BURST = 10;

	/** The default wall-time budget, in nanoseconds. */
	public static final long DEFAULT_WALL_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(60);

	/** The default wall-time budget window, in nanoseconds. */
	public static final long DEFAULT_WALL_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(10);

	private static final double NANOS_PER_HOUR = TimeUnit.HOURS.toNanos(1);

	private final LongSupplier nanoTime;
	private final Map<RateClass, Bucket> buckets = new EnumMap<>(RateClass.class);
	private final long wallBudgetNanos;
	private final double wallDrainPerNano;
	private double wallLevelNanos;
	private long wallLastNanos;

	/**
	 * Constructor.
	 *
	 * <p>
	 * The {@link System#nanoTime()} time source is used.
	 * </p>
	 */
	public RateLimiter() {
		this(System::nanoTime);
	}

	/**
	 * Constructor.
	 *
	 * @param nanoTime
	 *        the monotonic nanosecond time source
	 */
	public RateLimiter(LongSupplier nanoTime) {
		this(nanoTime, DEFAULT_WALL_BUDGET_NANOS, DEFAULT_WALL_WINDOW_NANOS);
	}

	/**
	 * Constructor.
	 *
	 * @param nanoTime
	 *        the monotonic nanosecond time source
	 * @param wallBudgetNanos
	 *        the maximum operation wall time allowed per window, in nanoseconds
	 * @param wallWindowNanos
	 *        the wall time window, in nanoseconds
	 * @throws IllegalArgumentException
	 *         if either wall time argument is not positive
	 */
	public RateLimiter(LongSupplier nanoTime, long wallBudgetNanos, long wallWindowNanos) {
		super();
		if ( wallBudgetNanos < 1 || wallWindowNanos < 1 ) {
			throw new IllegalArgumentException("The wall time budget and window must be positive.");
		}
		this.nanoTime = nanoTime;
		this.wallBudgetNanos = wallBudgetNanos;
		this.wallDrainPerNano = (double) wallBudgetNanos / (double) wallWindowNanos;
		final long now = nanoTime.getAsLong();
		this.wallLastNanos = now;
		buckets.put(RateClass.LIGHT, new Bucket(DEFAULT_LIGHT_PER_HOUR, LIGHT_BURST, LIGHT_BURST, now));
		buckets.put(RateClass.SCAN, new Bucket(DEFAULT_SCAN_PER_HOUR, SCAN_BURST, SCAN_BURST, now));
		buckets.put(RateClass.NONE, new Bucket(DEFAULT_NONE_PER_HOUR, NONE_BURST, NONE_BURST, now));
	}

	/**
	 * Try to acquire permission to run an operation.
	 *
	 * <p>
	 * When permission is granted a token is consumed from the bucket of the
	 * given rate class. When permission is denied nothing is consumed.
	 * </p>
	 *
	 * @param rateClass
	 *        the rate class of the operation
	 * @return {@code null} if permission is granted, otherwise the duration to
	 *         wait before trying again
	 */
	public synchronized @Nullable Duration tryAcquire(RateClass rateClass) {
		final long now = nanoTime.getAsLong();
		long retryNanos = 0;
		if ( rateClass != RateClass.NONE ) {
			drainWall(now);
			if ( wallLevelNanos >= wallBudgetNanos ) {
				retryNanos = (long) Math.ceil((wallLevelNanos - wallBudgetNanos) / wallDrainPerNano) + 1;
			}
		}
		final Bucket b = bucket(rateClass);
		b.refill(now);
		if ( b.tokens < 1.0 ) {
			retryNanos = Math.max(retryNanos, b.nanosUntilToken());
		}
		if ( retryNanos > 0 ) {
			return Duration.ofNanos(retryNanos);
		}
		b.tokens -= 1.0;
		return null;
	}

	/**
	 * Return a token previously acquired with {@link #tryAcquire(RateClass)},
	 * for a request that did not run.
	 *
	 * <p>
	 * The bucket is never filled beyond its burst size.
	 * </p>
	 *
	 * @param rateClass
	 *        the rate class of the operation
	 */
	public synchronized void release(RateClass rateClass) {
		final Bucket b = bucket(rateClass);
		b.refill(nanoTime.getAsLong());
		b.tokens = Math.min(b.burst, b.tokens + 1.0);
	}

	/**
	 * Charge operation wall time against the wall-time budget.
	 *
	 * @param nanos
	 *        the elapsed operation wall time, in nanoseconds; negative values
	 *        are ignored
	 */
	public synchronized void chargeWallTime(long nanos) {
		drainWall(nanoTime.getAsLong());
		if ( nanos > 0 ) {
			wallLevelNanos += nanos;
		}
	}

	/**
	 * Get the number of whole tokens currently available for a rate class.
	 *
	 * @param rateClass
	 *        the rate class
	 * @return the available tokens
	 */
	public synchronized int available(RateClass rateClass) {
		final Bucket b = bucket(rateClass);
		b.refill(nanoTime.getAsLong());
		return (int) Math.floor(b.tokens);
	}

	/**
	 * Get the configured rate per hour for a rate class.
	 *
	 * @param rateClass
	 *        the rate class
	 * @return the rate per hour
	 */
	public synchronized int getPerHour(RateClass rateClass) {
		return bucket(rateClass).perHour;
	}

	/**
	 * Set the rate per hour for a rate class.
	 *
	 * <p>
	 * The bucket is rebuilt with the new rate, keeping its current token level.
	 * </p>
	 *
	 * @param rateClass
	 *        the rate class
	 * @param perHour
	 *        the rate per hour; values less than {@code 1} are treated as
	 *        {@code 1}
	 */
	public synchronized void setPerHour(RateClass rateClass, int perHour) {
		final long now = nanoTime.getAsLong();
		final Bucket old = bucket(rateClass);
		old.refill(now);
		buckets.put(rateClass, new Bucket(Math.max(1, perHour), old.burst, old.tokens, now));
	}

	private Bucket bucket(RateClass rateClass) {
		final Bucket b = buckets.get(rateClass);
		if ( b == null ) {
			throw new IllegalArgumentException("Unsupported rate class.");
		}
		return b;
	}

	private void drainWall(long now) {
		final long elapsed = now - wallLastNanos;
		if ( elapsed > 0 ) {
			wallLevelNanos = Math.max(0.0, wallLevelNanos - elapsed * wallDrainPerNano);
			wallLastNanos = now;
		}
	}

	private static final class Bucket {

		private final int perHour;
		private final int burst;
		private final double tokensPerNano;
		private double tokens;
		private long lastNanos;

		private Bucket(int perHour, int burst, double tokens, long now) {
			super();
			this.perHour = perHour;
			this.burst = burst;
			this.tokensPerNano = perHour / NANOS_PER_HOUR;
			this.tokens = Math.min(burst, tokens);
			this.lastNanos = now;
		}

		private void refill(long now) {
			final long elapsed = now - lastNanos;
			if ( elapsed > 0 ) {
				tokens = Math.min(burst, tokens + elapsed * tokensPerNano);
				lastNanos = now;
			}
		}

		private long nanosUntilToken() {
			return Math.max(1L, (long) Math.ceil((1.0 - tokens) / tokensPerNano));
		}

	}

}
