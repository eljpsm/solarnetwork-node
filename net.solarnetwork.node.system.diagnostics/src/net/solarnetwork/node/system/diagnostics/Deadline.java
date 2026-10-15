/* ==================================================================
 * Deadline.java - 16/10/2026 9:00:00 am
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

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * A cooperative deadline.
 *
 * <p>
 * A deadline is also considered expired once the current thread has been
 * interrupted, so that cancelled work stops promptly.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public final class Deadline {

	private final LongSupplier nanoTime;
	private final long expiresAtNanos;

	/**
	 * Constructor.
	 *
	 * @param millis
	 *        the number of milliseconds from now until the deadline expires
	 * @param nanoTime
	 *        the monotonic nanosecond time source
	 */
	public Deadline(long millis, LongSupplier nanoTime) {
		super();
		this.nanoTime = nanoTime;
		this.expiresAtNanos = nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(millis);
	}

	/**
	 * Create a new deadline using {@link System#nanoTime()}.
	 *
	 * @param millis
	 *        the number of milliseconds from now until the deadline expires
	 * @return the new deadline
	 */
	public static Deadline ofMillis(long millis) {
		return new Deadline(millis, System::nanoTime);
	}

	/**
	 * Test if the deadline has expired.
	 *
	 * @return {@code true} if the deadline has expired or the current thread
	 *         has been interrupted
	 */
	public boolean expired() {
		return Thread.currentThread().isInterrupted() || nanoTime.getAsLong() - expiresAtNanos >= 0;
	}

	/**
	 * Get the number of milliseconds remaining.
	 *
	 * @return the remaining milliseconds, or {@code 0} if expired
	 */
	public long remainingMs() {
		long remaining = expiresAtNanos - nanoTime.getAsLong();
		return (remaining > 0 ? TimeUnit.NANOSECONDS.toMillis(remaining) : 0L);
	}

	/**
	 * Throw an exception if the deadline has expired.
	 *
	 * @throws DeadlineExceededException
	 *         if the deadline has expired
	 */
	public void check() {
		if ( expired() ) {
			throw new DeadlineExceededException();
		}
	}

}
