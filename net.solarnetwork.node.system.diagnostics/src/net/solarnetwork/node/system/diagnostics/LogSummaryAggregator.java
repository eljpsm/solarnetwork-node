/* ==================================================================
 * LogSummaryAggregator.java - 16/10/2026 9:00:00 am
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Aggregate normalised log entries into bounded pattern summaries.
 *
 * <p>
 * Entries are grouped by {@code level|logger|pattern}, up to {@link #MAX_KEYS}
 * keys. Entries for keys beyond that are counted as other patterns. Each
 * summary tracks a count, the first and last seen times, the most frequent
 * targets and sources, and the most recent root exception.
 * </p>
 *
 * <p>
 * When a source filter is configured, only entries whose extracted source
 * equals the filter are summarised; entries with other extracted sources are
 * counted per source instead.
 * </p>
 *
 * <p>
 * This class is not thread safe.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public final class LogSummaryAggregator {

	/** The maximum number of summary keys. */
	public static final int MAX_KEYS = 500;

	/** The maximum number of distinct targets or sources tracked per key. */
	public static final int MAX_VALUES_PER_KEY = 32;

	/** The maximum number of targets or sources output per summary. */
	public static final int MAX_VALUES_OUTPUT = 3;

	/** The maximum number of other sources tracked. */
	public static final int MAX_OTHER_SOURCES = 64;

	/** The maximum number of other sources output. */
	public static final int MAX_OTHER_SOURCES_OUTPUT = 10;

	/** The maximum number of overflow keys tracked, to estimate their count. */
	private static final int MAX_OVERFLOW_KEYS = 10_000;

	private final @Nullable String sourceFilter;
	private final Map<String, Summary> summaries = new HashMap<>(64);
	private final Map<String, Long> otherSources = new HashMap<>(16);
	private final Set<Integer> overflowKeys = new HashSet<>(16);
	private long overflowEntries;
	private long matched;

	/**
	 * Constructor.
	 *
	 * @param sourceFilter
	 *        an optional source ID to limit the summaries to
	 */
	public LogSummaryAggregator(@Nullable String sourceFilter) {
		super();
		this.sourceFilter = sourceFilter;
	}

	private static final class Summary {

		private final int levelRank;
		private final String level;
		private final String logger;
		private final String pattern;
		private final Map<String, Long> targets = new HashMap<>(4);
		private final Map<String, Long> sources = new HashMap<>(4);
		private long count;
		private long firstSeenMs = Long.MAX_VALUE;
		private long lastSeenMs = Long.MIN_VALUE;
		private long exceptionMs = Long.MIN_VALUE;
		private @Nullable String exception;

		private Summary(LogEntry entry, String pattern) {
			super();
			this.levelRank = entry.getLevelRank();
			this.level = entry.getLevel();
			this.logger = entry.getLogger();
			this.pattern = pattern;
		}

		private void add(LogEntry entry, PatternNormalizer.Normalized n) {
			final long ts = entry.getTimestampMs();
			count++;
			if ( ts < firstSeenMs ) {
				firstSeenMs = ts;
			}
			if ( ts > lastSeenMs ) {
				lastSeenMs = ts;
			}
			final String root = entry.getRootException();
			if ( root != null && ts >= exceptionMs ) {
				exceptionMs = ts;
				exception = root;
			}
			increment(targets, n.getTarget(), MAX_VALUES_PER_KEY);
			increment(sources, n.getSource(), MAX_VALUES_PER_KEY);
		}

		private Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>(10);
			m.put("level", level);
			m.put("logger", logger);
			m.put("pattern", pattern);
			m.put("count", count);
			m.put("firstSeenMs", firstSeenMs);
			m.put("lastSeenMs", lastSeenMs);
			m.put("targets", top(targets, MAX_VALUES_OUTPUT, "value"));
			m.put("sources", top(sources, MAX_VALUES_OUTPUT, "value"));
			final String ex = exception;
			if ( ex != null ) {
				m.put("exception", Map.of("root", ex));
			}
			return m;
		}

	}

	private static void increment(Map<String, Long> counts, @Nullable String value, int max) {
		if ( value == null ) {
			return;
		}
		Long c = counts.get(value);
		if ( c != null ) {
			counts.put(value, c + 1);
		} else if ( counts.size() < max ) {
			counts.put(value, 1L);
		}
	}

	private static List<Map<String, Object>> top(Map<String, Long> counts, int max, String key) {
		// @formatter:off
		return counts.entrySet().stream()
				.sorted(Map.Entry.<String, Long> comparingByValue().reversed()
						.thenComparing(Map.Entry.comparingByKey()))
				.limit(max)
				.map(e -> {
					Map<String, Object> m = new LinkedHashMap<>(2);
					m.put(key, e.getKey());
					m.put("count", e.getValue());
					return m;
				})
				.toList();
		// @formatter:on
	}

	/**
	 * Add an entry.
	 *
	 * <p>
	 * The entry is assumed to have already passed any level and time window
	 * filtering.
	 * </p>
	 *
	 * @param entry
	 *        the entry
	 * @param normalized
	 *        the normalised entry message
	 * @return {@code true} if the entry was summarised (or counted as an other
	 *         pattern), {@code false} if it was excluded by the source filter
	 */
	public boolean add(LogEntry entry, PatternNormalizer.Normalized normalized) {
		final String filter = sourceFilter;
		if ( filter != null ) {
			final String src = normalized.getSource();
			if ( !filter.equals(src) ) {
				if ( src != null ) {
					increment(otherSources, src, MAX_OTHER_SOURCES);
				}
				return false;
			}
		}
		matched++;
		final String key = entry.getLevel() + '|' + entry.getLogger() + '|' + normalized.getPattern();
		Summary s = summaries.get(key);
		if ( s == null ) {
			if ( summaries.size() >= MAX_KEYS ) {
				overflowEntries++;
				if ( overflowKeys.size() < MAX_OVERFLOW_KEYS ) {
					overflowKeys.add(key.hashCode());
				}
				return true;
			}
			s = new Summary(entry, normalized.getPattern());
			summaries.put(key, s);
		}
		s.add(entry, normalized);
		return true;
	}

	/**
	 * Get the number of entries summarised.
	 *
	 * @return the matched entry count
	 */
	public long getMatched() {
		return matched;
	}

	/**
	 * Get the number of distinct summary keys.
	 *
	 * @return the key count, not including overflow keys
	 */
	public int getKeyCount() {
		return summaries.size();
	}

	private List<Summary> sorted() {
		// @formatter:off
		Comparator<Summary> cmp = Comparator.<Summary> comparingInt(s -> s.levelRank).reversed()
				.thenComparing(Comparator.<Summary> comparingLong(s -> s.count).reversed())
				.thenComparing(Comparator.<Summary> comparingLong(s -> s.lastSeenMs).reversed())
				.thenComparing(s -> s.pattern)
				.thenComparing(s -> s.logger);
		// @formatter:on
		List<Summary> list = new ArrayList<>(summaries.values());
		list.sort(cmp);
		return list;
	}

	/**
	 * Get the summaries.
	 *
	 * <p>
	 * Summaries are sorted by level (most severe first), count (descending),
	 * last seen (descending), and then pattern.
	 * </p>
	 *
	 * @param limit
	 *        the maximum number of summaries to return
	 * @return the summaries, never {@code null}
	 */
	public List<Map<String, Object>> summaries(int limit) {
		List<Summary> list = sorted();
		List<Map<String, Object>> result = new ArrayList<>(Math.min(limit, list.size()));
		for ( int i = 0, len = Math.min(limit, list.size()); i < len; i++ ) {
			result.add(list.get(i).toMap());
		}
		return result;
	}

	/**
	 * Get the other patterns information.
	 *
	 * @param limit
	 *        the summary limit passed to {@link #summaries(int)}
	 * @return a map with {@code keys} and {@code entries} counts of patterns
	 *         not included in the summaries; the overflow key count is an
	 *         estimate
	 */
	public Map<String, Object> otherPatterns(int limit) {
		long keys = overflowKeys.size();
		long entries = overflowEntries;
		List<Summary> list = sorted();
		for ( int i = limit; i < list.size(); i++ ) {
			keys++;
			entries += list.get(i).count;
		}
		Map<String, Object> m = new LinkedHashMap<>(2);
		m.put("keys", keys);
		m.put("entries", entries);
		return m;
	}

	/**
	 * Get the other sources.
	 *
	 * @return the other sources, as maps with {@code source} and {@code count}
	 *         keys, sorted by count descending and then source, at most
	 *         {@link #MAX_OTHER_SOURCES_OUTPUT}
	 */
	public List<Map<String, Object>> otherSources() {
		return top(otherSources, MAX_OTHER_SOURCES_OUTPUT, "source");
	}

}
