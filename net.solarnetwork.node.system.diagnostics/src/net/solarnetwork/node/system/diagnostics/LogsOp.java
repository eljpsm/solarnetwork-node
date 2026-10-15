/* ==================================================================
 * LogsOp.java - 16/10/2026 9:00:00 am
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
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.node.system.diagnostics.DiagnosticResult.Reason;
import net.solarnetwork.node.system.diagnostics.LogFiles.LogFile;

/**
 * Summarise recent warnings and errors in the SolarNode log files.
 *
 * <p>
 * The SolarNode log files (see {@link LogFiles}) are scanned newest first with
 * a {@link LogScanner}. Entries at or above the requested minimum level and
 * within the requested time window have their messages redacted, normalised
 * with {@link PatternNormalizer}, and aggregated with
 * {@link LogSummaryAggregator}. No raw log lines are ever returned.
 * </p>
 *
 * <p>
 * If the deadline expires or the scan byte limit is reached, the partial
 * results collected so far are returned with {@code scanned.complete} set to
 * {@code false}. A file larger than the remaining scan byte limit has only its
 * end scanned, so that the most recent entries are always included.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public class LogsOp implements DiagnosticOp {

	/** The operation name. */
	public static final String NAME = "logs";

	/** The operation version. */
	public static final int VERSION = 1;

	private final DiagnosticsPaths paths;

	/**
	 * Constructor.
	 *
	 * @param paths
	 *        the paths
	 */
	public LogsOp(DiagnosticsPaths paths) {
		super();
		this.paths = paths;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public int version() {
		return VERSION;
	}

	@Override
	public RateClass rateClass() {
		return RateClass.SCAN;
	}

	@Override
	public Set<String> parameterNames() {
		return LogsQuery.PARAMETER_NAMES;
	}

	@Override
	public void validate(Map<String, String> params) {
		LogsQuery.validate(params);
	}

	@Override
	public void validate(Map<String, String> params, Instant now) {
		LogsQuery.parse(params, now);
	}

	@Override
	public @Nullable Map<String, Object> capabilities(OpContext ctx) {
		final Path dir = paths.getLogDir();
		Map<String, Object> m = new LinkedHashMap<>(4);
		LogFiles files = null;
		try {
			files = LogFiles.snapshot(dir);
		} catch ( IOException e ) {
			// treat as not supported
		}
		final List<Map<String, Object>> fileList = new ArrayList<>(4);
		LogLayout layout = LogLayout.UNSUPPORTED;
		if ( files != null ) {
			layout = files.getLayout();
			for ( LogFile f : files.getFiles() ) {
				fileList.add(f.toMap());
			}
		}
		m.put("supported", layout != LogLayout.UNSUPPORTED && !fileList.isEmpty());
		m.put("layout", layout.key());
		m.put("dir", dir.toString());
		m.put("files", fileList);
		return m;
	}

	@Override
	public DiagnosticResult run(OpContext ctx) throws Exception {
		final LogsQuery q = LogsQuery.parse(ctx.getParams(), ctx.getNow());
		final DiagnosticResult r = new DiagnosticResult();
		r.put("request", q.toMap());

		final Map<String, Object> window = new LinkedHashMap<>(2);
		window.put("requested", timeRange("sinceMs", q.getSinceMs(), "untilMs", q.getUntilMs()));
		r.put("window", window);

		final LogFiles files;
		try {
			files = LogFiles.snapshot(paths.getLogDir());
		} catch ( ClosedByInterruptException e ) {
			r.notAvailable(NAME, Reason.TIMEOUT);
			return r;
		} catch ( AccessDeniedException e ) {
			r.notAvailable(NAME, Reason.DENIED);
			return r;
		} catch ( IOException e ) {
			r.notAvailable(NAME, Reason.ERROR);
			return r;
		}
		if ( files == null || files.getFiles().isEmpty() ) {
			r.notAvailable(NAME, Reason.ABSENT);
			return r;
		}

		final LogLayout layout = files.getLayout();
		r.put("layout", layout.key());
		if ( layout == LogLayout.UNSUPPORTED ) {
			r.notAvailable(NAME, Reason.UNSUPPORTED);
			return r;
		}

		final Map<String, Object> available = new LinkedHashMap<>(2);
		final Long earliestMs = files.earliestMs(ctx.getZone());
		if ( earliestMs != null ) {
			available.put("earliestMs", earliestMs);
		}
		window.put("available", available);

		final LogScanner scanner = new LogScanner(layout, ctx.getZone(), ctx.getDeadline(),
				ctx.getMaxScanBytes());
		final LogSummaryAggregator agg = new LogSummaryAggregator(q.getSource());
		final Collector collector = new Collector(q, ctx.getRedactor(), agg);

		int filesRead = 0;
		int rotated = 0;
		boolean complete = true;
		try {
			for ( LogFile f : files.filesSince(q.getSinceMs()) ) {
				ctx.getDeadline().check();
				if ( scanner.getRemainingBytes() < 1 ) {
					complete = false;
					break;
				}
				try {
					final Path p = files.locate(f);
					if ( p == null ) {
						rotated++;
						continue;
					} else if ( !p.equals(f.getPath()) ) {
						rotated++;
					}
					try (FileChannel ch = LogFiles.open(p)) {
						filesRead++;
						// scan only the end of a file larger than the remaining budget
						final long offset = Math.max(0L, f.getSize() - scanner.getRemainingBytes());
						if ( offset > 0 ) {
							ch.position(offset);
							complete = false;
						}
						if ( !scanner.scan(ch, f.getSize() - offset, offset > 0, collector) ) {
							complete = false;
							break;
						}
					}
				} catch ( ClosedByInterruptException e ) {
					throw new DeadlineExceededException();
				} catch ( NoSuchFileException e ) {
					rotated++;
				} catch ( AccessDeniedException e ) {
					complete = false;
					r.notAvailable(NAME + ":" + f.getName(), Reason.DENIED);
				} catch ( IOException e ) {
					// includes failing to list the directory again to locate a rotated file
					complete = false;
					r.notAvailable(NAME + ":" + f.getName(), Reason.ERROR);
				}
			}
		} catch ( DeadlineExceededException e ) {
			complete = false;
			r.notAvailable(NAME + "/scan", Reason.TIMEOUT);
		}

		if ( collector.latestMs != Long.MIN_VALUE ) {
			available.put("latestMs", collector.latestMs);
		}
		if ( !complete && collector.earliestMs != Long.MAX_VALUE ) {
			window.put("scannedFromMs", collector.earliestMs);
		}

		final Map<String, Object> scanned = new LinkedHashMap<>(10);
		scanned.put("files", filesRead);
		scanned.put("bytes", scanner.getBytes());
		scanned.put("lines", scanner.getLines());
		scanned.put("entries", scanner.getEntries());
		scanned.put("matched", agg.getMatched());
		scanned.put("unparsed", scanner.getUnparsed());
		scanned.put("longLines", scanner.getLongLines());
		scanned.put("rotatedDuringScan", rotated > 0);
		scanned.put("complete", complete);
		r.put("scanned", scanned);

		r.put("summaries", agg.summaries(q.getLimit()));
		r.put("otherPatterns", agg.otherPatterns(q.getLimit()));
		if ( q.getSource() != null ) {
			r.put("otherSources", agg.otherSources());
		}
		return r;
	}

	private static Map<String, Object> timeRange(String k1, long v1, String k2, long v2) {
		Map<String, Object> m = new LinkedHashMap<>(2);
		m.put(k1, v1);
		m.put(k2, v2);
		return m;
	}

	private static final class Collector implements Consumer<LogEntry> {

		private final LogsQuery query;
		private final int minLevelRank;
		private final Redactor redactor;
		private final LogSummaryAggregator agg;
		private long latestMs = Long.MIN_VALUE;
		private long earliestMs = Long.MAX_VALUE;

		private Collector(LogsQuery query, Redactor redactor, LogSummaryAggregator agg) {
			super();
			this.query = query;
			this.minLevelRank = query.getMinLevelRank();
			this.redactor = redactor;
			this.agg = agg;
		}

		@Override
		public void accept(LogEntry entry) {
			final long ts = entry.getTimestampMs();
			if ( ts > latestMs ) {
				latestMs = ts;
			}
			if ( ts < earliestMs ) {
				earliestMs = ts;
			}
			if ( entry.getLevelRank() < minLevelRank || !query.inWindow(ts) ) {
				return;
			}
			final String redacted = redactor.redact(entry.getMessage());
			agg.add(entry, PatternNormalizer.normalize(redacted));
		}

	}

}
