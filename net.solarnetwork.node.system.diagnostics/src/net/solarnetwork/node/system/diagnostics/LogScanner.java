/* ==================================================================
 * LogScanner.java - 16/10/2026 9:00:00 am
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Bounded scanner of SolarNode log files.
 *
 * <p>
 * Bytes are read through a fixed size buffer and split into lines, each capped
 * at {@link #MAX_LINE_BYTES} bytes (the remainder of a longer line is
 * discarded). Lines are decoded as UTF-8, replacing malformed input. Any line
 * cut short (a long line, or a final line without a line ending) has its
 * trailing token-like run removed with
 * {@link Redactor#trimPartialToken(String)}, as a secret cut short may no
 * longer be recognised by the {@link Redactor}. Header lines are parsed with a
 * {@link LogLayout}; all other lines are continuations of the previous entry,
 * from which only the root exception class name is extracted. Lines before the
 * first header are counted as unparsed.
 * </p>
 *
 * <p>
 * Settings audit entries (logger names ending in
 * {@link LogEntry#AUDIT_LOGGER_SUFFIX}) are dropped along with their
 * continuation lines, as they contain unredacted setting values.
 * </p>
 *
 * <p>
 * The {@link Deadline} is checked on every line and every buffer read. A total
 * byte budget is shared across all
 * {@link #scan(ReadableByteChannel, long, Consumer)} calls on the same
 * instance.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public final class LogScanner {

	/** The read buffer size. */
	public static final int BUFFER_SIZE = 64 * 1024;

	/** The maximum number of bytes retained for any one line. */
	public static final int MAX_LINE_BYTES = 8 * 1024;

	private static final String CAUSED_BY = "Caused by: ";

	private static final String FQCN = "(?:[A-Za-z_$][A-Za-z0-9_$]{0,127}+\\.){1,32}+"
			+ "[A-Za-z_$][A-Za-z0-9_$]{0,127}+";

	/** A {@code Caused by:} line, up to the class name. */
	private static final Pattern CAUSED_BY_LINE = Pattern
			.compile("[ \\t]{0,16}+Caused by: (" + FQCN + ")(?![A-Za-z0-9_$.])");

	/** An exception line, starting with a class name. */
	private static final Pattern EXCEPTION_LINE = Pattern.compile("(" + FQCN + ")(?::|$)");

	private final LogLayout layout;
	private final ZoneId zone;
	private final Deadline deadline;
	private final long maxBytes;

	private final byte[] buf = new byte[BUFFER_SIZE];
	private final byte[] line = new byte[MAX_LINE_BYTES];

	private long bytes;
	private long lines;
	private long entries;
	private long unparsed;
	private long longLines;
	private long audit;

	/**
	 * Constructor.
	 *
	 * @param layout
	 *        the log layout; must not be {@link LogLayout#UNSUPPORTED}
	 * @param zone
	 *        the time zone of the log timestamps
	 * @param deadline
	 *        the deadline
	 * @param maxBytes
	 *        the maximum total number of bytes to read
	 * @throws IllegalArgumentException
	 *         if {@code layout} is {@link LogLayout#UNSUPPORTED}
	 */
	public LogScanner(LogLayout layout, ZoneId zone, Deadline deadline, long maxBytes) {
		super();
		if ( layout == LogLayout.UNSUPPORTED ) {
			throw new IllegalArgumentException("Unsupported layout");
		}
		this.layout = layout;
		this.zone = zone;
		this.deadline = deadline;
		this.maxBytes = maxBytes;
	}

	/**
	 * Scan a log file.
	 *
	 * @param ch
	 *        the channel to read from, positioned at the start of the file
	 * @param maxFileBytes
	 *        the maximum number of bytes to read from {@code ch}, for example
	 *        the size of the file when it was first listed
	 * @param consumer
	 *        the consumer of the parsed entries
	 * @return {@code true} if the file was read up to its end or
	 *         {@code maxFileBytes}, {@code false} if reading stopped because
	 *         the total byte budget was reached
	 * @throws IOException
	 *         if an IO error occurs
	 * @throws DeadlineExceededException
	 *         if the deadline expires
	 */
	public boolean scan(ReadableByteChannel ch, long maxFileBytes, Consumer<LogEntry> consumer)
			throws IOException {
		return scan(ch, maxFileBytes, false, consumer);
	}

	/**
	 * Scan a log file, optionally from a position within it.
	 *
	 * <p>
	 * When {@code tail} is {@code true} the channel is assumed to be positioned
	 * part way through the file, for example to scan only the end of a file
	 * larger than the remaining byte budget. The first (likely partial) line is
	 * then skipped, and continuation lines before the first header line are
	 * ignored without being counted as unparsed.
	 * </p>
	 *
	 * @param ch
	 *        the channel to read from
	 * @param maxFileBytes
	 *        the maximum number of bytes to read from {@code ch}
	 * @param tail
	 *        {@code true} if {@code ch} is positioned part way through the file
	 * @param consumer
	 *        the consumer of the parsed entries
	 * @return {@code true} if the file was read up to its end or
	 *         {@code maxFileBytes}, {@code false} if reading stopped because
	 *         the total byte budget was reached
	 * @throws IOException
	 *         if an IO error occurs
	 * @throws DeadlineExceededException
	 *         if the deadline expires
	 */
	public boolean scan(ReadableByteChannel ch, long maxFileBytes, boolean tail,
			Consumer<LogEntry> consumer) throws IOException {
		final FileState state = new FileState(consumer, tail);
		final ByteBuffer bb = ByteBuffer.wrap(buf);
		long fileRemaining = maxFileBytes;
		boolean complete = true;
		while ( fileRemaining > 0 ) {
			deadline.check();
			final long budget = maxBytes - bytes;
			if ( budget <= 0 ) {
				complete = false;
				break;
			}
			bb.clear();
			bb.limit((int) Math.min(buf.length, Math.min(fileRemaining, budget)));
			final int n = ch.read(bb);
			if ( n < 0 ) {
				break;
			}
			bytes += n;
			fileRemaining -= n;
			for ( int i = 0; i < n; i++ ) {
				final byte b = buf[i];
				if ( b == '\n' ) {
					state.endLine(false);
				} else {
					state.append(b);
				}
			}
		}
		state.finish();
		return complete;
	}

	private final class FileState {

		private final Consumer<LogEntry> consumer;
		private int len;
		private boolean overflow;
		private boolean seenHeader;
		private boolean skipLine;
		private @Nullable LogEntry current;

		private FileState(Consumer<LogEntry> consumer, boolean tail) {
			super();
			this.consumer = consumer;
			this.skipLine = tail;
			this.seenHeader = tail;
		}

		private void append(byte b) {
			if ( skipLine ) {
				return;
			}
			if ( len < line.length ) {
				line[len++] = b;
			} else {
				overflow = true;
			}
		}

		private void endLine(boolean cut) {
			if ( skipLine ) {
				skipLine = false;
				return;
			}
			int end = len;
			if ( end > 0 && line[end - 1] == '\r' ) {
				end--;
			}
			String s = new String(line, 0, end, StandardCharsets.UTF_8);
			if ( cut || overflow ) {
				// the line was cut before redaction: drop any partial secret at the cut
				s = Redactor.trimPartialToken(s);
			}
			processLine(s);
			len = 0;
			overflow = false;
		}

		private void processLine(String s) {
			deadline.check();
			lines++;
			if ( overflow ) {
				longLines++;
			}
			final LogEntry header = layout.parseHeader(s, zone);
			if ( header != null ) {
				emit();
				seenHeader = true;
				entries++;
				current = header;
				return;
			}
			final LogEntry entry = current;
			if ( entry == null ) {
				if ( !seenHeader && !s.isBlank() ) {
					unparsed++;
				}
				return;
			}
			continuation(entry, s);
		}

		private void finish() {
			if ( !skipLine && (len > 0 || overflow) ) {
				// no line ending: the line may have been cut by the snapshot size or byte budget
				endLine(true);
			}
			emit();
		}

		private void emit() {
			final LogEntry entry = current;
			current = null;
			if ( entry == null ) {
				return;
			}
			if ( entry.isAudit() ) {
				audit++;
				return;
			}
			consumer.accept(entry);
		}

	}

	private static void continuation(LogEntry entry, String s) {
		if ( entry.isAudit() || s.isEmpty() ) {
			return;
		}
		final int causedIdx = s.indexOf(CAUSED_BY);
		if ( causedIdx >= 0 && causedIdx <= 16 ) {
			Matcher m = CAUSED_BY_LINE.matcher(s);
			if ( m.lookingAt() ) {
				entry.causedBy(m.group(1));
			}
			return;
		}
		if ( entry.hasExceptionLine() || !Character.isJavaIdentifierStart(s.charAt(0)) ) {
			return;
		}
		Matcher m = EXCEPTION_LINE.matcher(s);
		if ( m.lookingAt() ) {
			final String name = m.group(1);
			if ( name.endsWith("Exception") || name.endsWith("Error") ) {
				entry.exceptionLine(name);
			}
		}
	}

	/**
	 * Read the first non-blank line of a channel.
	 *
	 * <p>
	 * At most {@link #BUFFER_SIZE} bytes are read, and the line is capped at
	 * {@link #MAX_LINE_BYTES} bytes.
	 * </p>
	 *
	 * @param ch
	 *        the channel to read
	 * @return the line, or {@code null} if none found
	 * @throws IOException
	 *         if an IO error occurs
	 */
	public static @Nullable String firstLine(ReadableByteChannel ch) throws IOException {
		final ByteBuffer bb = ByteBuffer.allocate(BUFFER_SIZE);
		while ( bb.hasRemaining() ) {
			if ( ch.read(bb) < 0 ) {
				break;
			}
		}
		final byte[] data = bb.array();
		final int n = bb.position();
		int start = 0;
		for ( int i = 0; i <= n; i++ ) {
			if ( i == n || data[i] == '\n' ) {
				int end = Math.min(i, start + MAX_LINE_BYTES);
				String s = new String(data, start, end - start, StandardCharsets.UTF_8);
				if ( !s.isBlank() ) {
					return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
				}
				start = i + 1;
			}
		}
		return null;
	}

	/**
	 * Get the total number of bytes read.
	 *
	 * @return the byte count
	 */
	public long getBytes() {
		return bytes;
	}

	/**
	 * Get the number of bytes remaining in the total byte budget.
	 *
	 * @return the remaining byte count
	 */
	public long getRemainingBytes() {
		return Math.max(0L, maxBytes - bytes);
	}

	/**
	 * Get the total number of lines read.
	 *
	 * @return the line count
	 */
	public long getLines() {
		return lines;
	}

	/**
	 * Get the total number of header lines parsed.
	 *
	 * @return the entry count, including dropped audit entries
	 */
	public long getEntries() {
		return entries;
	}

	/**
	 * Get the number of non-blank lines found before the first header line in
	 * each file.
	 *
	 * @return the unparsed line count
	 */
	public long getUnparsed() {
		return unparsed;
	}

	/**
	 * Get the number of lines longer than {@link #MAX_LINE_BYTES}.
	 *
	 * @return the long line count
	 */
	public long getLongLines() {
		return longLines;
	}

	/**
	 * Get the number of settings audit entries dropped.
	 *
	 * @return the audit entry count
	 */
	public long getAudit() {
		return audit;
	}

}
