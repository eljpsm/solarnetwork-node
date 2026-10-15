/* ==================================================================
 * LogsQuery.java - 16/10/2026 9:00:00 am
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Validated query parameters for the {@code logs} operation.
 *
 * <p>
 * The supported parameters are:
 * </p>
 *
 * <dl>
 * <dt>{@code sinceMs}, {@code untilMs}</dt>
 * <dd>Epoch millisecond window bounds (1 - 13 digits), where
 * {@code since < until}, the window is at most 14 days, and {@code until} is at
 * most 5 minutes after the current time. {@code untilMs} defaults to now and
 * {@code sinceMs} to 24 hours before {@code untilMs}.</dd>
 * <dt>{@code minLevel}</dt>
 * <dd>{@code WARN} (the default) or {@code ERROR}.</dd>
 * <dt>{@code source}</dt>
 * <dd>An exact source ID to match, {@code [A-Za-z0-9_./:~-]{1,64}}.</dd>
 * <dt>{@code limit}</dt>
 * <dd>The maximum number of summaries to return, 1 - 30 (default 20).</dd>
 * </dl>
 *
 * <p>
 * Validation exception messages name the invalid parameter but never include
 * its value.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public final class LogsQuery {

	/** The {@code sinceMs} parameter name. */
	public static final String PARAM_SINCE = "sinceMs";

	/** The {@code untilMs} parameter name. */
	public static final String PARAM_UNTIL = "untilMs";

	/** The {@code minLevel} parameter name. */
	public static final String PARAM_MIN_LEVEL = "minLevel";

	/** The {@code source} parameter name. */
	public static final String PARAM_SOURCE = "source";

	/** The {@code limit} parameter name. */
	public static final String PARAM_LIMIT = "limit";

	/** The supported parameter names. */
	public static final Set<String> PARAMETER_NAMES = Set.of(PARAM_SINCE, PARAM_UNTIL, PARAM_MIN_LEVEL,
			PARAM_SOURCE, PARAM_LIMIT);

	/** The maximum window length, in milliseconds. */
	public static final long MAX_WINDOW_MS = TimeUnit.DAYS.toMillis(14);

	/** The default window length, in milliseconds. */
	public static final long DEFAULT_WINDOW_MS = TimeUnit.HOURS.toMillis(24);

	/** The maximum amount of time {@code untilMs} can be after now. */
	public static final long MAX_FUTURE_MS = TimeUnit.MINUTES.toMillis(5);

	/** The default {@code limit} value. */
	public static final int DEFAULT_LIMIT = 20;

	/** The maximum {@code limit} value. */
	public static final int MAX_LIMIT = 30;

	/** The default {@code minLevel} value. */
	public static final String DEFAULT_MIN_LEVEL = "WARN";

	/** A valid source ID. */
	public static final Pattern SOURCE_ID = Pattern.compile("[A-Za-z0-9_./:~-]{1,64}+");

	private static final Pattern EPOCH_MS = Pattern.compile("[0-9]{1,13}+");

	private static final Pattern LIMIT = Pattern.compile("[1-9][0-9]?+");

	private final long sinceMs;
	private final long untilMs;
	private final String minLevel;
	private final @Nullable String source;
	private final int limit;

	private LogsQuery(long sinceMs, long untilMs, String minLevel, @Nullable String source, int limit) {
		super();
		this.sinceMs = sinceMs;
		this.untilMs = untilMs;
		this.minLevel = minLevel;
		this.source = source;
		this.limit = limit;
	}

	/**
	 * Validate parameters without reference to the current time.
	 *
	 * <p>
	 * This validates the syntax and range of every parameter, and the window
	 * when both {@code sinceMs} and {@code untilMs} are provided.
	 * </p>
	 *
	 * @param params
	 *        the parameters
	 * @throws IllegalArgumentException
	 *         if any parameter is invalid
	 */
	public static void validate(Map<String, String> params) {
		parseValues(params);
	}

	/**
	 * Parse and validate parameters, applying defaults.
	 *
	 * @param params
	 *        the parameters
	 * @param now
	 *        the current time
	 * @return the query, never {@code null}
	 * @throws IllegalArgumentException
	 *         if any parameter is invalid
	 */
	public static LogsQuery parse(Map<String, String> params, Instant now) {
		final Values v = parseValues(params);
		final long nowMs = now.toEpochMilli();
		final Long u = v.until;
		final long until = (u != null ? u : nowMs);
		if ( until > nowMs + MAX_FUTURE_MS ) {
			throw invalid(PARAM_UNTIL);
		}
		final Long s = v.since;
		final long since = (s != null ? s : Math.max(0L, until - DEFAULT_WINDOW_MS));
		checkWindow(since, until);
		return new LogsQuery(since, until, v.minLevel, v.source, v.limit);
	}

	private static final class Values {

		private @Nullable Long since;
		private @Nullable Long until;
		private String minLevel = DEFAULT_MIN_LEVEL;
		private @Nullable String source;
		private int limit = DEFAULT_LIMIT;

	}

	private static Values parseValues(Map<String, String> params) {
		final Values v = new Values();
		v.since = epochMs(params, PARAM_SINCE);
		v.until = epochMs(params, PARAM_UNTIL);
		final Long s = v.since;
		final Long u = v.until;
		if ( s != null && u != null ) {
			checkWindow(s, u);
		}
		final String level = params.get(PARAM_MIN_LEVEL);
		if ( level != null ) {
			if ( !("WARN".equals(level) || "ERROR".equals(level)) ) {
				throw invalid(PARAM_MIN_LEVEL);
			}
			v.minLevel = level;
		}
		final String source = params.get(PARAM_SOURCE);
		if ( source != null ) {
			if ( !SOURCE_ID.matcher(source).matches() ) {
				throw invalid(PARAM_SOURCE);
			}
			v.source = source;
		}
		final String limit = params.get(PARAM_LIMIT);
		if ( limit != null ) {
			if ( !LIMIT.matcher(limit).matches() ) {
				throw invalid(PARAM_LIMIT);
			}
			int l = Integer.parseInt(limit);
			if ( l > MAX_LIMIT ) {
				throw invalid(PARAM_LIMIT);
			}
			v.limit = l;
		}
		return v;
	}

	private static @Nullable Long epochMs(Map<String, String> params, String name) {
		final String val = params.get(name);
		if ( val == null ) {
			return null;
		}
		if ( !EPOCH_MS.matcher(val).matches() ) {
			throw invalid(name);
		}
		return Long.valueOf(val);
	}

	private static void checkWindow(long since, long until) {
		if ( since < 0 || since >= until || until - since > MAX_WINDOW_MS ) {
			throw invalid(PARAM_SINCE);
		}
	}

	private static IllegalArgumentException invalid(String name) {
		return new IllegalArgumentException("Invalid parameter: " + name);
	}

	/**
	 * Get the request parameters as a map, suitable for including in a result.
	 *
	 * @return the map with {@code sinceMs}, {@code untilMs}, {@code minLevel},
	 *         {@code limit}, and {@code source} if available
	 */
	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>(5);
		m.put(PARAM_SINCE, sinceMs);
		m.put(PARAM_UNTIL, untilMs);
		m.put(PARAM_MIN_LEVEL, minLevel);
		if ( source != null ) {
			m.put(PARAM_SOURCE, source);
		}
		m.put(PARAM_LIMIT, limit);
		return m;
	}

	/**
	 * Get the window start.
	 *
	 * @return the inclusive window start, as an epoch millisecond value
	 */
	public long getSinceMs() {
		return sinceMs;
	}

	/**
	 * Get the window end.
	 *
	 * @return the exclusive window end, as an epoch millisecond value
	 */
	public long getUntilMs() {
		return untilMs;
	}

	/**
	 * Get the minimum level.
	 *
	 * @return the minimum level name, {@code WARN} or {@code ERROR}
	 */
	public String getMinLevel() {
		return minLevel;
	}

	/**
	 * Get the minimum level severity rank.
	 *
	 * @return the rank
	 * @see LogEntry#levelRank(String)
	 */
	public int getMinLevelRank() {
		return LogEntry.levelRank(minLevel);
	}

	/**
	 * Get the source ID to match.
	 *
	 * @return the source ID, or {@code null} to match all
	 */
	public @Nullable String getSource() {
		return source;
	}

	/**
	 * Get the maximum number of summaries to return.
	 *
	 * @return the limit
	 */
	public int getLimit() {
		return limit;
	}

	/**
	 * Test if a timestamp is within the window.
	 *
	 * @param ms
	 *        the epoch millisecond timestamp
	 * @return {@code true} if {@code since <= ms < until}
	 */
	public boolean inWindow(long ms) {
		return ms >= sinceMs && ms < untilMs;
	}

}
