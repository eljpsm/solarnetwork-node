/* ==================================================================
 * ResultEncoderTests.java - 16/10/2026 9:00:00 am
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
import static org.assertj.core.api.BDDAssertions.catchThrowable;
import static org.assertj.core.api.BDDAssertions.then;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.junit.Test;
import net.solarnetwork.node.system.diagnostics.Redactor;
import net.solarnetwork.node.system.diagnostics.ResultEncoder;

/**
 * Test cases for the {@link ResultEncoder} class.
 *
 * @author elijah
 * @version 1.0
 */
public class ResultEncoderTests {

	/**
	 * Letters that cannot spell any redaction keyword (no vowels, no digits),
	 * so random text built from them is high-entropy but never redacted.
	 */
	private static final String ALPHABET = "bcdfghjklmnpqrstvwxzBCDFGHJKLMNPQRSTVWXZ";

	private static Map<String, Object> envelope(Map<String, Object> data) {
		Map<String, Object> node = new LinkedHashMap<>();
		node.put("nowMs", 1_760_000_000_000L);
		node.put("zone", "Pacific/Auckland");
		Map<String, Object> env = new LinkedHashMap<>();
		env.put("v", 1);
		env.put("op", "test");
		env.put("opV", 1);
		env.put("plugin", "1.0.0");
		env.put("tookMs", 12);
		env.put("node", node);
		env.put("data", data);
		env.put("truncated", new ArrayList<>());
		env.put("notAvailable", new ArrayList<>());
		env.put("redacted", 0);
		return env;
	}

	private static String randomText(Random rng, int len) {
		StringBuilder buf = new StringBuilder(len);
		for ( int i = 0; i < len; i++ ) {
			if ( i > 0 && i % 8 == 0 ) {
				buf.append(' ');
			} else {
				buf.append(ALPHABET.charAt(rng.nextInt(ALPHABET.length())));
			}
		}
		return buf.toString();
	}

	private static List<Object> randomItems(long seed, int count) {
		Random rng = new Random(seed);
		List<Object> items = new ArrayList<>(count);
		for ( int i = 0; i < count; i++ ) {
			items.add(randomText(rng, 40));
		}
		return items;
	}

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for ( int i = 0; i < kv.length; i += 2 ) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	@SuppressWarnings("unchecked")
	private static <T> T get(Map<String, Object> m, String key) {
		return (T) m.get(key);
	}

	@Test
	public void roundTrip() {
		// GIVEN
		Map<String, Object> data = map("a", "x", "n", 42, "list", Arrays.asList(1, 2, 3), "nested",
				map("b", true, "e", TimeUnit.SECONDS));
		Map<String, Object> env = envelope(data);
		Redactor redactor = new Redactor();

		// WHEN
		String result = ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, redactor);

		// THEN
		then(result).as("Result encoded").isNotEmpty().matches("[A-Za-z0-9+/=]+");
		Map<String, Object> decoded = ResultEncoder.decode(result);
		// @formatter:off
		then(decoded)
			.as("Envelope key order preserved")
			.containsOnlyKeys("v", "op", "opV", "plugin", "tookMs", "node", "data", "truncated",
					"notAvailable", "redacted")
			.containsEntry("v", 1)
			.containsEntry("op", "test")
			.containsEntry("opV", 1)
			.containsEntry("plugin", "1.0.0")
			.containsEntry("tookMs", 12)
			.containsEntry("redacted", redactor.count())
			;
		then(decoded.keySet())
			.as("Envelope key order preserved")
			.containsExactly("v", "op", "opV", "plugin", "tookMs", "node", "data", "truncated",
					"notAvailable", "redacted")
			;
		then(ResultEncoderTests.<Map<String, Object>> get(decoded, "node"))
			.as("Node info preserved")
			.containsEntry("nowMs", 1_760_000_000_000L)
			.containsEntry("zone", "Pacific/Auckland")
			;
		then(ResultEncoderTests.<Map<String, Object>> get(decoded, "data"))
			.as("Data preserved, enum as name")
			.containsEntry("a", "x")
			.containsEntry("n", 42)
			.containsEntry("list", Arrays.asList(1, 2, 3))
			.containsEntry("nested", map("b", true, "e", "SECONDS"))
			;
		// @formatter:on
	}

	@Test
	public void gzipTrailerValid() throws Exception {
		// GIVEN
		Map<String, Object> env = envelope(map("items", randomItems(1, 50)));

		// WHEN
		String result = ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, new Redactor());

		// THEN
		byte[] gz = Base64.getDecoder().decode(result);
		then(gz).as("Gzip magic").startsWith((byte) 0x1f, (byte) 0x8b);
		byte[] json;
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
			// reading to EOF verifies the trailer CRC and size
			json = in.readAllBytes();
		}
		then(new String(json, UTF_8)).as("JSON decompressed").startsWith("{\"v\":1,");
	}

	@Test
	public void smallResult_notTruncated() {
		// GIVEN
		List<Object> items = randomItems(2, 10);
		Map<String, Object> env = envelope(map("items", items));

		// WHEN
		String result = ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, new Redactor());

		// THEN
		then(result.length()).as("Within budget").isLessThanOrEqualTo(ResultEncoder.DEFAULT_BUDGET);
		Map<String, Object> decoded = ResultEncoder.decode(result);
		then(ResultEncoderTests.<List<Object>> get(decoded, "truncated")).as("Nothing truncated")
				.isEmpty();
		then(ResultEncoderTests.<Map<String, Object>> get(decoded, "data")).as("All items present")
				.containsEntry("items", items);
	}

	@Test
	public void highEntropy_truncatedToBudget() {
		// GIVEN
		List<Object> items = randomItems(3, 3000);
		Map<String, Object> env = envelope(map("count", 3000, "items", items));

		// WHEN
		String result = ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, new Redactor());

		// THEN
		then(result.length()).as("Within budget").isLessThanOrEqualTo(ResultEncoder.DEFAULT_BUDGET);
		Map<String, Object> decoded = ResultEncoder.decode(result);
		List<Map<String, Object>> truncated = get(decoded, "truncated");
		then(truncated).as("One truncation recorded").hasSize(1);
		Map<String, Object> t = truncated.get(0);
		// @formatter:off
		then(t)
			.as("Truncation path")
			.containsEntry("path", "data.items")
			.as("Truncation total is original size")
			.containsEntry("total", 3000)
			.as("Truncation reason")
			.containsEntry("reason", "budget")
			;
		// @formatter:on
		int kept = (Integer) t.get("kept");
		then(kept).as("Some items dropped, some kept").isLessThan(3000).isGreaterThan(0);
		Map<String, Object> data = get(decoded, "data");
		then(data).as("Other data preserved").containsEntry("count", 3000);
		then(ResultEncoderTests.<List<Object>> get(data, "items")).as("Head of list kept, in order")
				.isEqualTo(items.subList(0, kept));
		then(items).as("Input envelope not modified").hasSize(3000);
		then(ResultEncoderTests.<List<Object>> get(env, "truncated")).as("Input truncated not modified")
				.isEmpty();
	}

	@Test
	public void nestedListPath() {
		// GIVEN
		Map<String, Object> data = map("small", Arrays.asList(1, 2), "a",
				map("b", randomItems(4, 3000)));
		Map<String, Object> env = envelope(data);

		// WHEN
		String result = ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, new Redactor());

		// THEN
		then(result.length()).as("Within budget").isLessThanOrEqualTo(ResultEncoder.DEFAULT_BUDGET);
		Map<String, Object> decoded = ResultEncoder.decode(result);
		List<Map<String, Object>> truncated = get(decoded, "truncated");
		then(truncated).as("One truncation recorded").hasSize(1);
		then(truncated.get(0)).as("Nested path").containsEntry("path", "data.a.b").containsEntry("total",
				3000);
		then(ResultEncoderTests.<Map<String, Object>> get(decoded, "data")).as("Small list untouched")
				.containsEntry("small", Arrays.asList(1, 2));
	}

	@Test
	public void listInListPath() {
		// GIVEN
		Map<String, Object> row = map("name", "r", "values", randomItems(5, 3000));
		Map<String, Object> env = envelope(map("rows", Arrays.asList(map("name", "q"), row)));

		// WHEN
		String result = ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, new Redactor());

		// THEN
		then(result.length()).as("Within budget").isLessThanOrEqualTo(ResultEncoder.DEFAULT_BUDGET);
		Map<String, Object> decoded = ResultEncoder.decode(result);
		List<Map<String, Object>> truncated = get(decoded, "truncated");
		then(truncated).as("One truncation recorded").hasSize(1);
		then(truncated.get(0)).as("Indexed path").containsEntry("path", "data.rows[1].values");
	}

	@Test
	public void equalLists_firstTrimmedFirst() {
		// GIVEN
		Map<String, Object> data = map("x", randomItems(6, 2000), "y", randomItems(7, 2000));
		Map<String, Object> env = envelope(data);

		// WHEN
		String result = ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, new Redactor());

		// THEN
		then(result.length()).as("Within budget").isLessThanOrEqualTo(ResultEncoder.DEFAULT_BUDGET);
		Map<String, Object> decoded = ResultEncoder.decode(result);
		List<Map<String, Object>> truncated = get(decoded, "truncated");
		// @formatter:off
		then(truncated)
			.as("Both lists truncated, first in iteration order first")
			.extracting(m -> m.get("path"))
			.containsExactly("data.x", "data.y")
			;
		// @formatter:on
	}

	@Test
	public void existingTruncation_merged() {
		// GIVEN
		Map<String, Object> env = envelope(map("items", randomItems(8, 3000)));
		List<Object> truncatedIn = get(env, "truncated");
		truncatedIn.add(map("path", "data.items", "total", 5000, "kept", 3000, "reason", "limit"));

		// WHEN
		String result = ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, new Redactor());

		// THEN
		Map<String, Object> decoded = ResultEncoder.decode(result);
		List<Map<String, Object>> truncated = get(decoded, "truncated");
		then(truncated).as("Truncation merged by path").hasSize(1);
		// @formatter:off
		then(truncated.get(0))
			.as("Original total kept")
			.containsEntry("path", "data.items")
			.containsEntry("total", 5000)
			.containsEntry("reason", "budget")
			;
		// @formatter:on
		then((Integer) truncated.get(0).get("kept")).as("Kept updated").isLessThan(3000);
		then(truncatedIn.get(0)).as("Input entry not modified")
				.isEqualTo(map("path", "data.items", "total", 5000, "kept", 3000, "reason", "limit"));
	}

	@Test
	public void deterministic() {
		// GIVEN
		Map<String, Object> env = envelope(map("items", randomItems(9, 3000)));

		// WHEN
		String result1 = ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, new Redactor());
		String result2 = ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, new Redactor());

		// THEN
		then(result2).as("Same input produces same output").isEqualTo(result1);
	}

	@Test
	public void middleTruncatedValue_failsToDecode() {
		// GIVEN
		Map<String, Object> env = envelope(map("items", randomItems(10, 3000)));
		String result = ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, new Redactor());
		then(result.length()).as("Large result").isGreaterThan(2048);
		String head = result.substring(0, 512);
		String tail = result.substring(result.length() - 512);

		// WHEN
		Throwable withEllipsis = catchThrowable(() -> ResultEncoder.decode(head + "…" + tail));
		Throwable withoutEllipsis = catchThrowable(() -> ResultEncoder.decode(head + tail));

		// THEN
		then(withEllipsis).as("Ellipsis truncated value fails to decode")
				.isInstanceOf(IllegalArgumentException.class);
		then(withoutEllipsis).as("Spliced value fails to decode")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	public void hugeLeafInData_dataDropped() {
		// GIVEN
		Map<String, Object> env = envelope(map("blob", randomText(new Random(11), 100_000)));

		// WHEN
		String result = ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, new Redactor());

		// THEN
		then(result.length()).as("Within budget").isLessThanOrEqualTo(ResultEncoder.DEFAULT_BUDGET);
		Map<String, Object> decoded = ResultEncoder.decode(result);
		then(ResultEncoderTests.<Map<String, Object>> get(decoded, "data")).as("Data dropped").isEmpty();
		// @formatter:off
		then(ResultEncoderTests.<List<Map<String, Object>>> get(decoded, "truncated"))
			.as("Data drop recorded")
			.containsExactly(map("path", "data", "total", -1, "kept", 0, "reason", "budget"))
			;
		// @formatter:on
		then(decoded).as("Envelope preserved").containsEntry("op", "test").containsEntry("plugin",
				"1.0.0");
	}

	@Test
	public void hugeLeafOutsideData_fallback() {
		// GIVEN
		Map<String, Object> env = envelope(map("items", randomItems(12, 100)));
		env.put("plugin", randomText(new Random(13), 100_000));

		// WHEN
		String result = ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, new Redactor());

		// THEN
		then(result.length()).as("Fallback is small").isLessThanOrEqualTo(300);
		Map<String, Object> decoded = ResultEncoder.decode(result);
		// @formatter:off
		then(decoded)
			.as("Fallback result")
			.isEqualTo(map("v", 1, "op", "test", "opV", 1, "error", "budget"))
			;
		// @formatter:on
	}

	@Test
	public void fallback_longOpCut() {
		// GIVEN
		Map<String, Object> env = envelope(map());
		String op = randomText(new Random(14), 100_000);
		env.put("op", op);

		// WHEN
		String result = ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, new Redactor());

		// THEN
		then(result.length()).as("Fallback is small").isLessThanOrEqualTo(300);
		Map<String, Object> decoded = ResultEncoder.decode(result);
		then(decoded).as("Fallback op cut").containsEntry("op", op.substring(0, 64))
				.containsEntry("error", "budget");
	}

	@Test
	public void smallBudgets_neverExceeded() {
		// GIVEN
		Map<String, Object> env = envelope(map("items", randomItems(15, 3000)));

		for ( int budget : new int[] { 200, 500, 1000, 3000, 7000 } ) {
			// WHEN
			String result = ResultEncoder.encode(env, budget, new Redactor());

			// THEN
			then(result.length()).as("Budget %d not exceeded", budget).isLessThanOrEqualTo(budget);
			then(ResultEncoder.decode(result)).as("Budget %d decodes", budget).containsEntry("op",
					"test");
		}
	}

	@Test
	public void budgetTooSmall() {
		// GIVEN
		Map<String, Object> env = envelope(map("items", randomItems(16, 10)));

		// WHEN
		Throwable t = catchThrowable(() -> ResultEncoder.encode(env, 10, new Redactor()));

		// THEN
		then(t).as("Budget too small for even the fallback").isInstanceOf(IllegalStateException.class)
				.hasMessage("Result budget too small");
	}

	@Test
	public void emoji_roundTrip() {
		// GIVEN
		String s = "☀️ solar 😀 👍🏽 zürich 日本";
		List<Object> items = new ArrayList<>();
		Random rng = new Random(17);
		for ( int i = 0; i < 3000; i++ ) {
			items.add("😀" + randomText(rng, 20) + "🌞");
		}
		Map<String, Object> env = envelope(map("s", s, "items", items));

		// WHEN
		String result = ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, new Redactor());

		// THEN
		then(result.length()).as("Within budget").isLessThanOrEqualTo(ResultEncoder.DEFAULT_BUDGET);
		Map<String, Object> decoded = ResultEncoder.decode(result);
		Map<String, Object> data = get(decoded, "data");
		then(data).as("Surrogate pairs preserved").containsEntry("s", s);
		List<Object> decodedItems = get(data, "items");
		then(decodedItems).as("Items truncated").isNotEmpty().hasSizeLessThan(3000);
		then(decodedItems).as("Item surrogate pairs preserved")
				.isEqualTo(items.subList(0, decodedItems.size()));
	}

	@Test
	public void redactedCount() {
		// GIVEN
		Map<String, Object> env = envelope(map("msg", "password=hunter2secret", "list",
				Arrays.asList("token: abcdef1234567890abcdef1234567890", "ok")));
		env.put("redacted", 99);
		Redactor redactor = new Redactor();
		redactor.redact("apiKey=1234567890abcdef");

		// WHEN
		String result = ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, redactor);

		// THEN
		Map<String, Object> decoded = ResultEncoder.decode(result);
		then(decoded).as("Redacted count is the redactor count").containsEntry("redacted",
				redactor.count());
		then(decoded.keySet()).as("Redacted key position preserved").endsWith("redacted");
	}

	@Test
	public void nullValues() {
		// GIVEN
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("a", null);
		data.put("b", Arrays.asList("x", null));
		Map<String, Object> env = envelope(data);

		// WHEN
		String result = ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, new Redactor());

		// THEN
		Map<String, Object> decoded = ResultEncoder.decode(result);
		then(ResultEncoderTests.<Map<String, Object>> get(decoded, "data"))
				.as("Null map value omitted, null list element kept").doesNotContainKey("a")
				.containsEntry("b", Arrays.asList("x", null));
	}

	@Test
	public void tooDeep() {
		// GIVEN
		Map<String, Object> data = map();
		Map<String, Object> m = data;
		for ( int i = 0; i < 100; i++ ) {
			Map<String, Object> child = map();
			m.put("c", child);
			m = child;
		}
		Map<String, Object> env = envelope(data);

		// WHEN
		Throwable t = catchThrowable(
				() -> ResultEncoder.encode(env, ResultEncoder.DEFAULT_BUDGET, new Redactor()));

		// THEN
		then(t).as("Deep nesting rejected").isInstanceOf(IllegalStateException.class)
				.hasMessage("Result nested too deeply");
	}

}
