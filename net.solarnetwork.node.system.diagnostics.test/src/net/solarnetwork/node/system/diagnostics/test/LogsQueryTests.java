/* ==================================================================
 * LogsQueryTests.java - 16/10/2026 9:00:00 am
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

import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenIllegalArgumentException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import net.solarnetwork.node.system.diagnostics.LogsQuery;

/**
 * Test cases for the {@link LogsQuery} class.
 *
 * @author elijah
 * @version 1.0
 */
public class LogsQueryTests {

	private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
	private static final long NOW_MS = NOW.toEpochMilli();
	private static final long DAY_MS = TimeUnit.DAYS.toMillis(1);

	private static Map<String, String> params(String... kv) {
		Map<String, String> m = new HashMap<>();
		for ( int i = 0; i < kv.length; i += 2 ) {
			m.put(kv[i], kv[i + 1]);
		}
		return m;
	}

	@Test
	public void defaults() {
		// WHEN
		LogsQuery q = LogsQuery.parse(Map.of(), NOW);

		// THEN
		// @formatter:off
		then(q)
			.as("Until defaults to now")
			.returns(NOW_MS, LogsQuery::getUntilMs)
			.as("Since defaults to 24h before until")
			.returns(NOW_MS - DAY_MS, LogsQuery::getSinceMs)
			.as("Min level defaults to WARN")
			.returns("WARN", LogsQuery::getMinLevel)
			.as("No source by default")
			.returns(null, LogsQuery::getSource)
			.as("Limit defaults to 20")
			.returns(20, LogsQuery::getLimit)
			;
		then(q.toMap())
			.as("Request map excludes source when not provided")
			.containsOnlyKeys("sinceMs", "untilMs", "minLevel", "limit")
			;
		// @formatter:on
	}

	@Test
	public void explicit() {
		// GIVEN
		final long until = NOW_MS - 1000;
		final long since = until - 14 * DAY_MS;

		// WHEN
		LogsQuery q = LogsQuery.parse(params("sinceMs", String.valueOf(since), "untilMs",
				String.valueOf(until), "minLevel", "ERROR", "source", "/SITE-C/INV/3", "limit", "30"),
				NOW);

		// THEN
		// @formatter:off
		then(q)
			.returns(since, LogsQuery::getSinceMs)
			.returns(until, LogsQuery::getUntilMs)
			.returns("ERROR", LogsQuery::getMinLevel)
			.returns("/SITE-C/INV/3", LogsQuery::getSource)
			.returns(30, LogsQuery::getLimit)
			;
		then(q.inWindow(since)).as("Since inclusive").isTrue();
		then(q.inWindow(until)).as("Until exclusive").isFalse();
		then(q.toMap())
			.containsEntry("source", "/SITE-C/INV/3")
			;
		// @formatter:on
	}

	@Test
	public void untilOnly_sinceDefaultsRelativeToUntil() {
		// GIVEN
		final long until = NOW_MS - 7 * DAY_MS;

		// WHEN
		LogsQuery q = LogsQuery.parse(params("untilMs", String.valueOf(until)), NOW);

		// THEN
		then(q.getSinceMs()).isEqualTo(until - DAY_MS);
	}

	@Test
	public void untilSlightlyInFuture() {
		// GIVEN
		final long until = NOW_MS + TimeUnit.MINUTES.toMillis(5);

		// WHEN
		LogsQuery q = LogsQuery.parse(params("untilMs", String.valueOf(until)), NOW);

		// THEN
		then(q.getUntilMs()).isEqualTo(until);
	}

	private static void thenInvalid(Map<String, String> params, String name, String value) {
		// @formatter:off
		thenIllegalArgumentException()
			.as("Parameter %s invalid", name)
			.isThrownBy(() -> LogsQuery.parse(params, NOW))
			.withMessage("Invalid parameter: %s", name)
			.satisfies(e -> {
				if ( value.length() > 2 ) {
					then(e.getMessage()).as("Value not echoed").doesNotContain(value);
				}
			})
			;
		// @formatter:on
	}

	@Test
	public void invalid_window() {
		thenInvalid(params("sinceMs", String.valueOf(NOW_MS - 1000), "untilMs",
				String.valueOf(NOW_MS - 1000)), "sinceMs", "x");
		thenInvalid(params("sinceMs", String.valueOf(NOW_MS - 1000), "untilMs",
				String.valueOf(NOW_MS - 2000)), "sinceMs", "x");
		thenInvalid(params("sinceMs", String.valueOf(NOW_MS - 15 * DAY_MS)), "sinceMs", "x");
		thenInvalid(params("sinceMs", String.valueOf(NOW_MS + 1000)), "sinceMs", "x");
		thenInvalid(params("untilMs", String.valueOf(NOW_MS + TimeUnit.MINUTES.toMillis(5) + 1)),
				"untilMs", "x");
	}

	@Test
	public void invalid_syntax() {
		thenInvalid(params("sinceMs", "12345678901234"), "sinceMs", "12345678901234");
		thenInvalid(params("sinceMs", "-1"), "sinceMs", "-1");
		thenInvalid(params("sinceMs", "1e3"), "sinceMs", "1e3");
		thenInvalid(params("sinceMs", ""), "sinceMs", "");
		thenInvalid(params("untilMs", " 1000"), "untilMs", " 1000");
		thenInvalid(params("minLevel", "INFO"), "minLevel", "INFO");
		thenInvalid(params("minLevel", "warn"), "minLevel", "warn");
		thenInvalid(params("minLevel", "FATAL"), "minLevel", "FATAL");
		thenInvalid(params("limit", "0"), "limit", "0");
		thenInvalid(params("limit", "31"), "limit", "31");
		thenInvalid(params("limit", "01"), "limit", "01");
		thenInvalid(params("limit", "100"), "limit", "100");
		thenInvalid(params("limit", "abc"), "limit", "abc");
		thenInvalid(params("source", "a".repeat(65)), "source", "a".repeat(65));
		thenInvalid(params("source", "S".repeat(101)), "source", "S".repeat(101));
		thenInvalid(params("source", "/SITE C/INV"), "source", "/SITE C/INV");
		thenInvalid(params("source", "/INV;DROP"), "source", "/INV;DROP");
		thenInvalid(params("source", "secret-value\n"), "source", "secret-value");
		thenInvalid(params("source", ""), "source", "");
	}

	@Test
	public void validate_syntaxOnly() {
		// GIVEN
		final Map<String, String> p = params("untilMs", String.valueOf(NOW_MS + DAY_MS));

		// WHEN
		LogsQuery.validate(p);

		// THEN
		thenInvalid(p, "untilMs", "x");
	}

	@Test
	public void validate_invalid() {
		// @formatter:off
		thenIllegalArgumentException()
			.isThrownBy(() -> LogsQuery.validate(params("limit", "31")))
			.withMessage("Invalid parameter: limit")
			;
		thenIllegalArgumentException()
			.isThrownBy(() -> LogsQuery.validate(params("sinceMs", "1000", "untilMs", "1000")))
			.withMessage("Invalid parameter: sinceMs")
			;
		thenIllegalArgumentException()
			.isThrownBy(() -> LogsQuery.validate(params("sinceMs", "1000",
					"untilMs", String.valueOf(1000 + 14 * DAY_MS + 1))))
			.withMessage("Invalid parameter: sinceMs")
			;
		// @formatter:on
	}

	@Test
	public void validate_valid() {
		// WHEN
		LogsQuery.validate(params("sinceMs", "1000", "untilMs", String.valueOf(1000 + 14 * DAY_MS),
				"minLevel", "ERROR", "source", "Mock_Meter.1:~-", "limit", "1"));

		// THEN
		then(LogsQuery.PARAMETER_NAMES).containsExactlyInAnyOrder("sinceMs", "untilMs", "minLevel",
				"source", "limit");
	}

}
