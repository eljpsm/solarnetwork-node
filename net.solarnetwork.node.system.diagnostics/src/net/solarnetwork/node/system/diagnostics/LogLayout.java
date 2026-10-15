/* ==================================================================
 * LogLayout.java - 16/10/2026 9:00:00 am
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

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/**
 * A supported SolarNode log file layout.
 *
 * <p>
 * The supported layouts are those of the SolarNode example log4j2
 * configuration, {@code %d{DEFAULT} %-5p %40.40c; %msg%n}, which produces lines
 * like:
 * </p>
 *
 * <pre>{@code
 * 2026-10-15 06:00:00,123 ERROR .node.datum.modbus.ModbusDatumDataSource; Communication problem...
 * }</pre>
 *
 * <p>
 * Headers are parsed by fixed columns: a timestamp, a space, a level
 * left-justified to 5 characters, a space, a logger name right-justified and
 * left-truncated to exactly 40 characters, and then {@code "; "} followed by
 * the message.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public enum LogLayout {

	/** The default layout, with a {@code yyyy-MM-dd HH:mm:ss,SSS} timestamp. */
	DEFAULT("default", 23),

	/**
	 * The layout used by nodes installed before 2022-05-28, with a
	 * {@code yyyy-MM-dd HH:mm:ss} timestamp.
	 */
	NO_MILLIS("noMillis", 19),

	/** An unsupported layout. */
	UNSUPPORTED("unsupported", 0),

	;

	/** The level column width. */
	public static final int LEVEL_WIDTH = 5;

	/** The logger column width. */
	public static final int LOGGER_WIDTH = 40;

	/** The separator between the logger column and the message. */
	public static final String MESSAGE_SEPARATOR = "; ";

	private final String key;
	private final int timestampLength;

	private LogLayout(String key, int timestampLength) {
		this.key = key;
		this.timestampLength = timestampLength;
	}

	/**
	 * Get the key value.
	 *
	 * @return the key
	 */
	public String key() {
		return key;
	}

	/**
	 * Get the timestamp column length.
	 *
	 * @return the length, or {@code 0} for {@link #UNSUPPORTED}
	 */
	public int getTimestampLength() {
		return timestampLength;
	}

	/**
	 * Get the offset of the message within a header line.
	 *
	 * @return the message offset
	 */
	public int getMessageOffset() {
		return timestampLength + 1 + LEVEL_WIDTH + 1 + LOGGER_WIDTH + MESSAGE_SEPARATOR.length();
	}

	/**
	 * Detect the layout of a header line.
	 *
	 * @param line
	 *        the line to inspect
	 * @return the layout, never {@code null}
	 */
	public static LogLayout detect(String line) {
		if ( DEFAULT.parseHeader(line, ZoneOffset.UTC) != null ) {
			return DEFAULT;
		}
		if ( NO_MILLIS.parseHeader(line, ZoneOffset.UTC) != null ) {
			return NO_MILLIS;
		}
		return UNSUPPORTED;
	}

	/**
	 * Parse a header line.
	 *
	 * <p>
	 * Local timestamps are resolved in {@code zone} with
	 * {@link LocalDateTime#atZone(ZoneId)}: the earlier offset is used during
	 * an overlap, and a time in a gap is shifted forward by the length of the
	 * gap.
	 * </p>
	 *
	 * @param line
	 *        the line to parse
	 * @param zone
	 *        the time zone of the log timestamps
	 * @return the parsed entry, or {@code null} if {@code line} is not a header
	 *         in this layout
	 */
	public @Nullable LogEntry parseHeader(String line, ZoneId zone) {
		final int t = timestampLength;
		if ( t < 1 || line.length() < getMessageOffset() ) {
			return null;
		}
		final LocalDateTime ts = parseTimestamp(line);
		if ( ts == null || line.charAt(t) != ' ' || line.charAt(t + 1 + LEVEL_WIDTH) != ' ' ) {
			return null;
		}
		final String level = line.substring(t + 1, t + 1 + LEVEL_WIDTH).trim();
		if ( LogEntry.levelRank(level) < 0 || line.charAt(t + 1) == ' ' ) {
			return null;
		}
		final int loggerStart = t + 1 + LEVEL_WIDTH + 1;
		final int loggerEnd = loggerStart + LOGGER_WIDTH;
		if ( !line.startsWith(MESSAGE_SEPARATOR, loggerEnd) ) {
			return null;
		}
		// right-justified logger: leading spaces, then non-space characters
		int i = loggerStart;
		while ( i < loggerEnd && line.charAt(i) == ' ' ) {
			i++;
		}
		if ( i == loggerEnd ) {
			return null;
		}
		for ( int j = i; j < loggerEnd; j++ ) {
			if ( Character.isWhitespace(line.charAt(j)) || Character.isISOControl(line.charAt(j)) ) {
				return null;
			}
		}
		final String logger = line.substring(i, loggerEnd);
		final String message = line.substring(loggerEnd + MESSAGE_SEPARATOR.length());
		final long ms = ts.atZone(zone).toInstant().toEpochMilli();
		return new LogEntry(ms, level, logger, message);
	}

	private @Nullable LocalDateTime parseTimestamp(String line) {
		// yyyy-MM-dd HH:mm:ss[,SSS]
		if ( line.charAt(4) != '-' || line.charAt(7) != '-' || line.charAt(10) != ' '
				|| line.charAt(13) != ':' || line.charAt(16) != ':' ) {
			return null;
		}
		final int year = digits(line, 0, 4);
		final int month = digits(line, 5, 2);
		final int day = digits(line, 8, 2);
		final int hour = digits(line, 11, 2);
		final int minute = digits(line, 14, 2);
		final int second = digits(line, 17, 2);
		int millis = 0;
		if ( this == DEFAULT ) {
			if ( line.charAt(19) != ',' ) {
				return null;
			}
			millis = digits(line, 20, 3);
		}
		if ( year < 0 || month < 0 || day < 0 || hour < 0 || minute < 0 || second < 0 || millis < 0 ) {
			return null;
		}
		try {
			return LocalDateTime.of(year, month, day, hour, minute, second, millis * 1_000_000);
		} catch ( DateTimeException e ) {
			return null;
		}
	}

	private static int digits(String s, int offset, int len) {
		int result = 0;
		for ( int i = offset, end = offset + len; i < end; i++ ) {
			char c = s.charAt(i);
			if ( c < '0' || c > '9' ) {
				return -1;
			}
			result = result * 10 + (c - '0');
		}
		return result;
	}

}
