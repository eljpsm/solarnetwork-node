/* ==================================================================
 * RateLimiterTests.java - 16/10/2026 9:00:00 am
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
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;
import net.solarnetwork.node.system.diagnostics.DiagnosticOp.RateClass;
import net.solarnetwork.node.system.diagnostics.RateLimiter;

/**
 * Test cases for the {@link RateLimiter} class.
 *
 * @author elijah
 * @version 1.0
 */
public class RateLimiterTests {

	private AtomicLong nanos;
	private RateLimiter limiter;

	@Before
	public void setup() {
		// start at an arbitrary, negative monotonic time to prove only differences are used
		nanos = new AtomicLong(-123_456_789_000L);
		limiter = new RateLimiter(nanos::get);
	}

	private void advance(long amount, TimeUnit unit) {
		nanos.addAndGet(unit.toNanos(amount));
	}

	@Test
	public void lightBurst() {
		// WHEN
		for ( int i = 0; i < RateLimiter.LIGHT_BURST; i++ ) {
			then(limiter.tryAcquire(RateClass.LIGHT)).as("Burst token %d allowed", i).isNull();
		}
		Duration retry = limiter.tryAcquire(RateClass.LIGHT);

		// THEN
		then(retry).as("Burst exhausted").isNotNull();
		then(limiter.available(RateClass.LIGHT)).isZero();
		then(limiter.available(RateClass.SCAN)).as("Other buckets unaffected")
				.isEqualTo(RateLimiter.SCAN_BURST);
	}

	@Test
	public void retryAfter() {
		// GIVEN
		for ( int i = 0; i < RateLimiter.SCAN_BURST; i++ ) {
			limiter.tryAcquire(RateClass.SCAN);
		}

		// WHEN
		Duration retry = limiter.tryAcquire(RateClass.SCAN);
		advance(60, TimeUnit.SECONDS);
		Duration retry2 = limiter.tryAcquire(RateClass.SCAN);

		// THEN
		// @formatter:off
		then(retry)
			.as("20/h refill means one token every 3 minutes")
			.isNotNull()
			.isBetween(Duration.ofSeconds(179), Duration.ofSeconds(181))
			;
		then(retry2)
			.as("Retry after shrinks as time passes")
			.isNotNull()
			.isBetween(Duration.ofSeconds(119), Duration.ofSeconds(121))
			;
		// @formatter:on
	}

	@Test
	public void refill() {
		// GIVEN
		for ( int i = 0; i < RateLimiter.LIGHT_BURST; i++ ) {
			limiter.tryAcquire(RateClass.LIGHT);
		}

		// WHEN
		advance(61, TimeUnit.SECONDS);
		Duration first = limiter.tryAcquire(RateClass.LIGHT);
		Duration second = limiter.tryAcquire(RateClass.LIGHT);
		advance(10, TimeUnit.HOURS);

		// THEN
		then(first).as("One token refilled after one minute at 60/h").isNull();
		then(second).as("Only one token refilled").isNotNull();
		then(limiter.available(RateClass.LIGHT)).as("Refill capped at burst")
				.isEqualTo(RateLimiter.LIGHT_BURST);
	}

	@Test
	public void deniedDoesNotConsume() {
		// GIVEN
		for ( int i = 0; i < RateLimiter.SCAN_BURST; i++ ) {
			limiter.tryAcquire(RateClass.SCAN);
		}

		// WHEN
		for ( int i = 0; i < 10; i++ ) {
			limiter.tryAcquire(RateClass.SCAN);
		}
		advance(181, TimeUnit.SECONDS);

		// THEN
		then(limiter.tryAcquire(RateClass.SCAN)).as("Denied attempts consumed nothing").isNull();
	}

	@Test
	public void release() {
		// GIVEN
		for ( int i = 0; i < RateLimiter.SCAN_BURST; i++ ) {
			limiter.tryAcquire(RateClass.SCAN);
		}

		// WHEN
		limiter.release(RateClass.SCAN);
		Duration first = limiter.tryAcquire(RateClass.SCAN);
		Duration second = limiter.tryAcquire(RateClass.SCAN);
		for ( int i = 0; i < 5; i++ ) {
			limiter.release(RateClass.LIGHT);
		}

		// THEN
		then(first).as("Released token can be acquired again").isNull();
		then(second).as("Only one token released").isNotNull();
		then(limiter.available(RateClass.LIGHT)).as("Release never exceeds burst")
				.isEqualTo(RateLimiter.LIGHT_BURST);
	}

	@Test
	public void noneBucket() {
		// GIVEN
		limiter.chargeWallTime(TimeUnit.MINUTES.toNanos(5));

		// WHEN
		for ( int i = 0; i < RateLimiter.NONE_BURST; i++ ) {
			then(limiter.tryAcquire(RateClass.NONE))
					.as("NONE token %d allowed despite wall-time budget", i).isNull();
		}
		Duration retry = limiter.tryAcquire(RateClass.NONE);

		// THEN
		// @formatter:off
		then(retry)
			.as("120/h refill means one token every 30 seconds")
			.isNotNull()
			.isBetween(Duration.ofSeconds(29), Duration.ofSeconds(31))
			;
		// @formatter:on
		then(limiter.available(RateClass.LIGHT)).isEqualTo(RateLimiter.LIGHT_BURST);
	}

	@Test
	public void wallTimeBudget() {
		// GIVEN
		limiter.chargeWallTime(TimeUnit.SECONDS.toNanos(30));
		then(limiter.tryAcquire(RateClass.LIGHT)).as("Under budget allowed").isNull();
		limiter.chargeWallTime(TimeUnit.SECONDS.toNanos(40));

		// WHEN
		Duration retry = limiter.tryAcquire(RateClass.LIGHT);
		Duration scanRetry = limiter.tryAcquire(RateClass.SCAN);

		// THEN
		// @formatter:off
		then(retry)
			.as("70s used of 60s per 10 min drains 10s over 100s")
			.isNotNull()
			.isBetween(Duration.ofSeconds(99), Duration.ofSeconds(101))
			;
		then(scanRetry)
			.as("Wall-time budget shared with SCAN")
			.isNotNull()
			.isBetween(Duration.ofSeconds(99), Duration.ofSeconds(101))
			;
		// @formatter:on
		then(limiter.available(RateClass.LIGHT)).as("Denied attempt consumed no token")
				.isEqualTo(RateLimiter.LIGHT_BURST - 1);
	}

	@Test
	public void wallTimeBudget_recovery() {
		// GIVEN
		limiter.chargeWallTime(TimeUnit.SECONDS.toNanos(90));
		then(limiter.tryAcquire(RateClass.LIGHT)).isNotNull();

		// WHEN
		advance(299, TimeUnit.SECONDS);
		Duration stillDenied = limiter.tryAcquire(RateClass.LIGHT);
		advance(2, TimeUnit.SECONDS);
		Duration allowed = limiter.tryAcquire(RateClass.LIGHT);

		// THEN
		then(stillDenied).as("30s over budget drains in 300s").isNotNull();
		then(allowed).as("Allowed once drained below budget").isNull();
	}

	@Test
	public void wallTimeBudget_negativeCharge() {
		// WHEN
		limiter.chargeWallTime(-TimeUnit.HOURS.toNanos(1));
		limiter.chargeWallTime(TimeUnit.SECONDS.toNanos(61));

		// THEN
		then(limiter.tryAcquire(RateClass.LIGHT)).as("Negative charge ignored").isNotNull();
	}

	@Test
	public void timeGoingBackwards() {
		// GIVEN
		for ( int i = 0; i < RateLimiter.LIGHT_BURST; i++ ) {
			limiter.tryAcquire(RateClass.LIGHT);
		}

		// WHEN
		advance(-10, TimeUnit.HOURS);
		Duration retry = limiter.tryAcquire(RateClass.LIGHT);

		// THEN
		then(retry).as("No refill when time goes backwards").isNotNull();
		then(limiter.available(RateClass.LIGHT)).isZero();
	}

	@Test
	public void setPerHour() {
		// GIVEN
		for ( int i = 0; i < RateLimiter.LIGHT_BURST; i++ ) {
			limiter.tryAcquire(RateClass.LIGHT);
		}

		// WHEN
		limiter.setPerHour(RateClass.LIGHT, 3600);
		Duration denied = limiter.tryAcquire(RateClass.LIGHT);
		advance(1100, TimeUnit.MILLISECONDS);
		Duration allowed = limiter.tryAcquire(RateClass.LIGHT);

		// THEN
		then(limiter.getPerHour(RateClass.LIGHT)).isEqualTo(3600);
		then(denied).as("Rebuilt bucket keeps current token level").isNotNull()
				.isLessThanOrEqualTo(Duration.ofSeconds(1));
		then(allowed).as("New rate applied").isNull();
	}

	@Test
	public void setPerHour_minimum() {
		// WHEN
		limiter.setPerHour(RateClass.SCAN, 0);

		// THEN
		then(limiter.getPerHour(RateClass.SCAN)).isEqualTo(1);
		then(limiter.available(RateClass.SCAN)).isEqualTo(RateLimiter.SCAN_BURST);
	}

}
