/* ==================================================================
 * DiagnosticOp.java - 16/10/2026 9:00:00 am
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
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * API for a single read-only diagnostic operation.
 *
 * <p>
 * Implementations must be read-only and must honour the {@link Deadline} in the
 * provided {@link OpContext}, returning partial results when it expires rather
 * than blocking.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public interface DiagnosticOp {

	/**
	 * A rate-limiting class for an operation.
	 */
	enum RateClass {

		/** Not rate limited. */
		NONE,

		/** A light-weight operation, such as reading a few small files. */
		LIGHT,

		/** A scanning operation, such as reading log files. */
		SCAN,

	}

	/**
	 * Get the operation name, used as the {@code op} instruction parameter
	 * value.
	 *
	 * @return the name, never {@code null}
	 */
	String name();

	/**
	 * Get the operation result version.
	 *
	 * @return the version
	 */
	int version();

	/**
	 * Get the rate-limiting class of this operation.
	 *
	 * @return the rate class, never {@code null}
	 */
	RateClass rateClass();

	/**
	 * Get the set of operation-specific parameter names this operation accepts.
	 *
	 * @return the parameter names, never {@code null}
	 */
	default Set<String> parameterNames() {
		return Set.of();
	}

	/**
	 * Validate the operation parameters.
	 *
	 * <p>
	 * This is called before any rate limiting is applied, so invalid requests
	 * do not consume rate tokens. Exception messages must name the offending
	 * parameter but must never include its value.
	 * </p>
	 *
	 * @param params
	 *        the single-valued operation parameters, limited to
	 *        {@link #parameterNames()}
	 * @throws IllegalArgumentException
	 *         if any parameter is invalid
	 */
	default void validate(Map<String, String> params) {
		// nothing by default
	}

	/**
	 * Validate the operation parameters, including any checks that depend on
	 * the current time.
	 *
	 * <p>
	 * This is called before any rate limiting is applied, so invalid requests
	 * do not consume rate tokens. The same {@code now} is later provided to
	 * {@link #run(OpContext)} via {@link OpContext#getNow()}. Exception
	 * messages must name the offending parameter but must never include its
	 * value. This default implementation calls {@link #validate(Map)}.
	 * </p>
	 *
	 * @param params
	 *        the single-valued operation parameters, limited to
	 *        {@link #parameterNames()}
	 * @param now
	 *        the current time
	 * @throws IllegalArgumentException
	 *         if any parameter is invalid
	 */
	default void validate(Map<String, String> params, Instant now) {
		validate(params);
	}

	/**
	 * Run the operation.
	 *
	 * @param ctx
	 *        the operation context
	 * @return the result, never {@code null}
	 * @throws Exception
	 *         if any error occurs
	 */
	DiagnosticResult run(OpContext ctx) throws Exception;

	/**
	 * Get operation-specific capability information.
	 *
	 * <p>
	 * This must be fast and must not perform any blocking I/O beyond listing a
	 * small directory. It is called on the operation's worker thread with a
	 * hard deadline, so that a hung file system cannot block the caller.
	 * </p>
	 *
	 * @param ctx
	 *        the operation context
	 * @return the capability information, or {@code null} if there is none
	 */
	default @Nullable Map<String, Object> capabilities(OpContext ctx) {
		return null;
	}

}
