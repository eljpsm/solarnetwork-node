/* ==================================================================
 * LogEntry.java - 16/10/2026 9:00:00 am
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

import org.jspecify.annotations.Nullable;

/**
 * A single parsed SolarNode log entry.
 *
 * <p>
 * Only the header fields and the root exception class name of an entry are
 * kept: continuation line text is never retained.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public final class LogEntry {

	/** The supported log level names, in increasing severity. */
	private static final String[] LEVELS = new String[] { "TRACE", "DEBUG", "INFO", "WARN", "ERROR",
			"FATAL" };

	/** The settings audit logger name suffix. */
	public static final String AUDIT_LOGGER_SUFFIX = "settings.AUDIT";

	private final long timestampMs;
	private final String level;
	private final String logger;
	private final String message;
	private @Nullable String rootException;
	private @Nullable String firstException;

	/**
	 * Constructor.
	 *
	 * @param timestampMs
	 *        the entry timestamp, as an epoch millisecond value
	 * @param level
	 *        the level name, one of {@code TRACE}, {@code DEBUG}, {@code INFO},
	 *        {@code WARN}, {@code ERROR}, or {@code FATAL}
	 * @param logger
	 *        the (trimmed, possibly truncated) logger name
	 * @param message
	 *        the first line of the message
	 */
	public LogEntry(long timestampMs, String level, String logger, String message) {
		super();
		this.timestampMs = timestampMs;
		this.level = level;
		this.logger = logger;
		this.message = message;
	}

	/**
	 * Get the severity rank of a level name.
	 *
	 * @param level
	 *        the level name
	 * @return the rank, from {@code 0} for {@code TRACE} to {@code 5} for
	 *         {@code FATAL}, or {@code -1} if {@code level} is not a supported
	 *         level name
	 */
	public static int levelRank(String level) {
		for ( int i = 0; i < LEVELS.length; i++ ) {
			if ( LEVELS[i].equals(level) ) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Get the level name for a severity rank.
	 *
	 * @param rank
	 *        the rank
	 * @return the level name
	 * @throws IllegalArgumentException
	 *         if {@code rank} is not valid
	 */
	public static String levelName(int rank) {
		if ( rank < 0 || rank >= LEVELS.length ) {
			throw new IllegalArgumentException("Invalid level rank");
		}
		return LEVELS[rank];
	}

	/**
	 * Get the entry timestamp.
	 *
	 * @return the timestamp, as an epoch millisecond value
	 */
	public long getTimestampMs() {
		return timestampMs;
	}

	/**
	 * Get the level name.
	 *
	 * @return the level name
	 */
	public String getLevel() {
		return level;
	}

	/**
	 * Get the level severity rank.
	 *
	 * @return the rank
	 * @see #levelRank(String)
	 */
	public int getLevelRank() {
		return levelRank(level);
	}

	/**
	 * Get the logger name.
	 *
	 * @return the trimmed logger name, which might have been truncated from the
	 *         left by the log layout
	 */
	public String getLogger() {
		return logger;
	}

	/**
	 * Get the first line of the message.
	 *
	 * <p>
	 * This is raw, unredacted text.
	 * </p>
	 *
	 * @return the message
	 */
	public String getMessage() {
		return message;
	}

	/**
	 * Test if this entry is from the settings audit logger.
	 *
	 * @return {@code true} if the logger name ends with
	 *         {@link #AUDIT_LOGGER_SUFFIX}
	 */
	public boolean isAudit() {
		final int len = AUDIT_LOGGER_SUFFIX.length();
		return logger.length() >= len
				&& logger.regionMatches(true, logger.length() - len, AUDIT_LOGGER_SUFFIX, 0, len);
	}

	/**
	 * Get the root exception class name.
	 *
	 * <p>
	 * This is the last {@code Caused by:} exception class name found in the
	 * continuation lines, or else the first exception class name found at the
	 * start of a continuation line.
	 * </p>
	 *
	 * @return the fully qualified class name, or {@code null} if none found
	 */
	public @Nullable String getRootException() {
		return (rootException != null ? rootException : firstException);
	}

	/**
	 * Record a {@code Caused by:} exception class name.
	 *
	 * @param className
	 *        the fully qualified class name
	 */
	void causedBy(String className) {
		this.rootException = className;
	}

	/**
	 * Record an exception class name found at the start of a continuation line.
	 *
	 * <p>
	 * Only the first such name is kept.
	 * </p>
	 *
	 * @param className
	 *        the fully qualified class name
	 */
	void exceptionLine(String className) {
		if ( this.firstException == null ) {
			this.firstException = className;
		}
	}

	/**
	 * Test if an exception class name has been recorded from a continuation
	 * line that is not a {@code Caused by:} line.
	 *
	 * @return {@code true} if a first exception line has been found
	 */
	boolean hasExceptionLine() {
		return firstException != null;
	}

}
