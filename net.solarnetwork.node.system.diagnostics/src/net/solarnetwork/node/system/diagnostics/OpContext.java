/* ==================================================================
 * OpContext.java - 16/10/2026 9:00:00 am
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

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

/**
 * Context for running a {@link DiagnosticOp}.
 *
 * @author elijah
 * @version 1.0
 */
public final class OpContext {

	private final Map<String, String> params;
	private final Instant now;
	private final ZoneId zone;
	private final Deadline deadline;
	private final Redactor redactor;
	private final long maxScanBytes;

	/**
	 * Constructor.
	 *
	 * @param params
	 *        the validated, single-valued operation parameters
	 * @param now
	 *        the current time
	 * @param zone
	 *        the node time zone
	 * @param deadline
	 *        the soft deadline
	 * @param redactor
	 *        the redactor to use for any free text
	 * @param maxScanBytes
	 *        the maximum number of bytes to read when scanning files
	 */
	public OpContext(Map<String, String> params, Instant now, ZoneId zone, Deadline deadline,
			Redactor redactor, long maxScanBytes) {
		super();
		this.params = params;
		this.now = now;
		this.zone = zone;
		this.deadline = deadline;
		this.redactor = redactor;
		this.maxScanBytes = maxScanBytes;
	}

	/**
	 * Get the operation parameters.
	 *
	 * @return the parameters, never {@code null}
	 */
	public Map<String, String> getParams() {
		return params;
	}

	/**
	 * Get a parameter value.
	 *
	 * @param name
	 *        the parameter name
	 * @return the value
	 */
	public Optional<String> param(String name) {
		return Optional.ofNullable(params.get(name));
	}

	/**
	 * Get the current time.
	 *
	 * @return the current time
	 */
	public Instant getNow() {
		return now;
	}

	/**
	 * Get the node time zone.
	 *
	 * @return the zone
	 */
	public ZoneId getZone() {
		return zone;
	}

	/**
	 * Get the soft deadline.
	 *
	 * @return the deadline
	 */
	public Deadline getDeadline() {
		return deadline;
	}

	/**
	 * Get the redactor.
	 *
	 * @return the redactor
	 */
	public Redactor getRedactor() {
		return redactor;
	}

	/**
	 * Get the maximum number of bytes to read when scanning files.
	 *
	 * @return the maximum scan bytes
	 */
	public long getMaxScanBytes() {
		return maxScanBytes;
	}

}
