/* ==================================================================
 * LogSummaryAggregatorTests.java - 16/10/2026 9:00:00 am
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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Map.entry;
import static org.assertj.core.api.BDDAssertions.then;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import net.solarnetwork.node.system.diagnostics.Deadline;
import net.solarnetwork.node.system.diagnostics.LogEntry;
import net.solarnetwork.node.system.diagnostics.LogLayout;
import net.solarnetwork.node.system.diagnostics.LogScanner;
import net.solarnetwork.node.system.diagnostics.LogSummaryAggregator;
import net.solarnetwork.node.system.diagnostics.PatternNormalizer;

/**
 * Test cases for the {@link LogSummaryAggregator} class.
 *
 * @author elijah
 * @version 1.0
 */
public class LogSummaryAggregatorTests {

	private static final String LOGGER = ".node.datum.modbus.ModbusDatumDataSource";

	private static LogEntry logEntry(long ts, String level, String msg) {
		return new LogEntry(ts, level, LOGGER, msg);
	}

	private static LogEntry scanned(long ts, String msg, String continuation) {
		final String data = String.format("1970-01-01 00:00:%02d,%03d ERROR %40s; %s\n%s\n", ts / 1000,
				ts % 1000, LOGGER, msg, continuation);
		final List<LogEntry> result = new ArrayList<>();
		try {
			new LogScanner(LogLayout.DEFAULT, ZoneOffset.UTC, Deadline.ofMillis(10_000), 1_000_000).scan(
					Channels.newChannel(new ByteArrayInputStream(data.getBytes(UTF_8))), Long.MAX_VALUE,
					result::add);
		} catch ( IOException e ) {
			throw new UncheckedIOException(e);
		}
		then(result).hasSize(1);
		then(result.get(0).getTimestampMs()).isEqualTo(ts);
		return result.get(0);
	}

	private static String letters(int i) {
		StringBuilder buf = new StringBuilder();
		do {
			buf.append((char) ('a' + (i % 26)));
			i /= 26;
		} while ( i > 0 );
		return buf.toString();
	}

	private static void add(LogSummaryAggregator agg, long ts, String level, String msg) {
		agg.add(logEntry(ts, level, msg), PatternNormalizer.normalize(msg));
	}

	private static String comm(String source, String target, String msg) {
		return "Communication problem reading source " + source + " from Modbus device " + target + ": "
				+ msg;
	}

	@Test
	public void grouping() {
		// GIVEN
		LogSummaryAggregator agg = new LogSummaryAggregator(null);

		// WHEN
		add(agg, 3000, "ERROR", comm("/INV/3", "10.0.0.3:502", "No route to host"));
		add(agg, 1000, "ERROR", comm("/INV/3", "10.0.0.3:502", "No route to host"));
		add(agg, 2000, "ERROR", comm("/INV/1", "10.0.0.1:502", "No route to host"));
		add(agg, 4000, "ERROR", comm("/INV/3", "10.0.0.3:502", "No route to host"));
		add(agg, 5000, "WARN", comm("/INV/3", "10.0.0.3:502", "No route to host"));

		// THEN
		List<Map<String, Object>> summaries = agg.summaries(10);
		// @formatter:off
		then(agg.getMatched()).isEqualTo(5L);
		then(agg.getKeyCount()).isEqualTo(2);
		then(summaries).hasSize(2);
		then(summaries.get(0))
			.containsEntry("level", "ERROR")
			.containsEntry("logger", LOGGER)
			.containsEntry("pattern", "Communication problem reading source <source> from Modbus "
					+ "device <target>: No route to host")
			.containsEntry("count", 4L)
			.containsEntry("firstSeenMs", 1000L)
			.containsEntry("lastSeenMs", 4000L)
			.containsEntry("targets", List.of(
					Map.of("value", "10.0.0.3:502", "count", 3L),
					Map.of("value", "10.0.0.1:502", "count", 1L)))
			.containsEntry("sources", List.of(
					Map.of("value", "/INV/3", "count", 3L),
					Map.of("value", "/INV/1", "count", 1L)))
			.doesNotContainKey("exception")
			;
		then(summaries.get(1))
			.containsEntry("level", "WARN")
			.containsEntry("count", 1L)
			;
		then(agg.otherPatterns(10)).containsOnly(entry("keys", 0L), entry("entries", 0L));
		then(agg.otherSources()).isEmpty();
		// @formatter:on
	}

	@Test
	public void topValuesLimited() {
		// GIVEN
		LogSummaryAggregator agg = new LogSummaryAggregator(null);

		// WHEN
		for ( int i = 0; i < 40; i++ ) {
			for ( int j = 0; j <= i % 5; j++ ) {
				add(agg, i, "ERROR", comm("/INV/" + i, "10.0.0." + i + ":502", "timeout"));
			}
		}

		// THEN
		Map<String, Object> s = agg.summaries(1).get(0);
		// @formatter:off
		then(s.get("targets"))
			.as("Top 3 by count, then value")
			.isEqualTo(List.of(
					Map.of("value", "10.0.0.14:502", "count", 5L),
					Map.of("value", "10.0.0.19:502", "count", 5L),
					Map.of("value", "10.0.0.24:502", "count", 5L)))
			;
		then(s.get("count")).isEqualTo(120L);
		// @formatter:on
	}

	@Test
	public void exceptionMostRecent() {
		// GIVEN
		LogSummaryAggregator agg = new LogSummaryAggregator(null);
		final String msg = comm("/INV/3", "10.0.0.3:502", "No route to host");

		// WHEN
		for ( long ts : new long[] { 2000, 3000, 1000 } ) {
			LogEntry e = scanned(ts, msg, "Caused by: java.lang.Ex" + ts + "Exception: x");
			agg.add(e, PatternNormalizer.normalize(msg));
		}
		agg.add(logEntry(4000, "ERROR", msg), PatternNormalizer.normalize(msg));

		// THEN
		then(agg.summaries(1).get(0)).containsEntry("exception",
				Map.of("root", "java.lang.Ex3000Exception"));
	}

	@Test
	public void sourceFilter() {
		// GIVEN
		LogSummaryAggregator agg = new LogSummaryAggregator("/INV/3");

		// WHEN
		for ( int i = 0; i < 70; i++ ) {
			for ( int j = 0; j <= i % 3; j++ ) {
				then(agg.add(logEntry(i, "ERROR", comm("/INV/1" + i, "1.2.3.4:502", "x")),
						PatternNormalizer.normalize(comm("/INV/1" + i, "1.2.3.4:502", "x")))).isFalse();
			}
		}
		add(agg, 1, "ERROR", comm("/INV/3", "1.2.3.4:502", "x"));
		add(agg, 2, "ERROR", "No source here");

		// THEN
		// @formatter:off
		then(agg.getMatched()).as("Only exact source matches summarised").isEqualTo(1L);
		then(agg.summaries(10)).hasSize(1);
		then(agg.otherSources())
			.as("Top 10 other sources by count then source, from at most 64 tracked")
			.hasSize(10)
			.first()
			.isEqualTo(Map.of("source", "/INV/111", "count", 3L))
			;
		// @formatter:on
	}

	@Test
	public void sortingAndLimit() {
		// GIVEN
		LogSummaryAggregator agg = new LogSummaryAggregator(null);

		// WHEN
		add(agg, 1, "WARN", "warn a");
		add(agg, 2, "WARN", "warn a");
		add(agg, 3, "WARN", "warn a");
		add(agg, 1, "ERROR", "error b");
		add(agg, 5, "ERROR", "error c");
		add(agg, 5, "ERROR", "error a");
		add(agg, 1, "FATAL", "fatal");

		// THEN
		// @formatter:off
		then(agg.summaries(30))
			.extracting(m -> m.get("pattern"))
			.as("Level, count desc, lastSeen desc, pattern")
			.containsExactly("fatal", "error a", "error c", "error b", "warn a")
			;
		then(agg.summaries(2)).hasSize(2);
		then(agg.otherPatterns(2)).containsOnly(entry("keys", 3L), entry("entries", 5L));
		// @formatter:on
	}

	@Test
	public void maxKeys() {
		// GIVEN
		LogSummaryAggregator agg = new LogSummaryAggregator(null);

		// WHEN
		for ( int i = 0; i < LogSummaryAggregator.MAX_KEYS + 10; i++ ) {
			String msg = "message " + letters(i) + " q";
			add(agg, i, "ERROR", msg);
			add(agg, i, "ERROR", msg);
		}

		// THEN
		// @formatter:off
		then(agg.getKeyCount()).isEqualTo(LogSummaryAggregator.MAX_KEYS);
		then(agg.getMatched()).isEqualTo(2L * (LogSummaryAggregator.MAX_KEYS + 10));
		then(agg.otherPatterns(30))
			.containsOnly(
				entry("keys", (long) (LogSummaryAggregator.MAX_KEYS - 30 + 10)),
				entry("entries", 2L * (LogSummaryAggregator.MAX_KEYS - 30 + 10)))
			;
		// @formatter:on
	}

}
