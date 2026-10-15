/* ==================================================================
 * ResultEncoder.java - 16/10/2026 9:00:00 am
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

import static java.nio.charset.StandardCharsets.UTF_8;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.codec.JsonUtils;

/**
 * Encode a diagnostic result envelope as a size-bounded, gzip compressed,
 * Base64 encoded JSON string.
 *
 * <p>
 * Every string value in the envelope is passed through a {@link Redactor}
 * before serialisation. If the encoded result is larger than the given budget,
 * the longest list found under the envelope's {@code data} map is shortened
 * from its tail (operations sort lists most-important-first) and the change is
 * recorded in the envelope's {@code truncated} list, repeating until the result
 * fits. If that is not enough the {@code data} map is emptied, and as a last
 * resort a minimal {@code {v, op, opV, error:"budget"}} result is returned.
 * </p>
 *
 * <p>
 * To decode a result: Base64 decode, then gunzip, then parse the UTF-8 JSON.
 * See {@link #decode(String)}.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public final class ResultEncoder {

	/** The default maximum encoded result length. */
	public static final int DEFAULT_BUDGET = 7000;

	/** The maximum JSON length to compress, trimmed without compression. */
	private static final int MAX_JSON_LENGTH = 262_144;

	/** The maximum number of compressed trim passes. */
	private static final int MAX_TRIM_PASSES = 40;

	/** The maximum nesting depth of envelope values. */
	private static final int MAX_DEPTH = 32;

	/** The maximum fallback string value length, in code points. */
	private static final int MAX_FALLBACK_STRING_LENGTH = 64;

	/** The maximum decoded JSON length, in bytes. */
	private static final int MAX_DECODED_LENGTH = 16 * 1024 * 1024;

	private static final String DATA_KEY = "data";
	private static final String TRUNCATED_KEY = "truncated";
	private static final String REDACTED_KEY = "redacted";
	private static final String BUDGET_REASON = "budget";

	private ResultEncoder() {
		// not available
	}

	/**
	 * Encode a result envelope.
	 *
	 * <p>
	 * The given envelope is not modified: it is deep copied (with every string
	 * value redacted) before any trimming. Map entries with {@code null} values
	 * are omitted. The copy's {@code redacted} value is set to the redactor's
	 * count after copying.
	 * </p>
	 *
	 * @param envelope
	 *        the envelope; must contain {@code v}, {@code op}, {@code opV}, a
	 *        {@code data} map and a {@code truncated} list
	 * @param budget
	 *        the maximum encoded length
	 * @param redactor
	 *        the redactor to apply to every string value
	 * @return the encoded result, never longer than {@code budget}
	 * @throws IllegalStateException
	 *         if the envelope cannot be serialised, is nested too deeply, or if
	 *         even the minimal fallback result does not fit within
	 *         {@code budget}
	 */
	public static String encode(Map<String, Object> envelope, int budget, Redactor redactor) {
		final Map<String, Object> copy = copyMap(envelope, redactor, 0);
		copy.put(REDACTED_KEY, redactor.count());
		final List<@Nullable Object> truncated = truncatedList(copy);

		// cheap pre-trim without compression
		String json = toJson(copy);
		while ( json.length() > MAX_JSON_LENGTH && trimLongestList(copy, truncated) ) {
			json = toJson(copy);
		}
		if ( json.length() > MAX_JSON_LENGTH && dropData(copy, truncated) ) {
			json = toJson(copy);
		}

		if ( json.length() <= MAX_JSON_LENGTH ) {
			String result = compress(json);
			for ( int pass = 0; result.length() > budget && pass < MAX_TRIM_PASSES
					&& trimLongestList(copy, truncated); pass++ ) {
				result = compress(toJson(copy));
			}
			if ( result.length() > budget && dropData(copy, truncated) ) {
				result = compress(toJson(copy));
			}
			if ( result.length() <= budget ) {
				return result;
			}
		}
		return fallback(copy, budget);
	}

	/**
	 * Decode a result previously encoded by
	 * {@link #encode(Map, int, Redactor)}.
	 *
	 * @param encoded
	 *        the encoded result
	 * @return the decoded result envelope
	 * @throws IllegalArgumentException
	 *         if the result cannot be decoded
	 */
	public static Map<String, Object> decode(String encoded) {
		final byte[] gz = Base64.getDecoder().decode(encoded);
		final byte[] json;
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
			json = in.readNBytes(MAX_DECODED_LENGTH + 1);
		} catch ( IOException e ) {
			throw new IllegalArgumentException("Invalid result compression", e);
		}
		if ( json.length > MAX_DECODED_LENGTH ) {
			throw new IllegalArgumentException("Result too large");
		}
		final Map<String, Object> result = JsonUtils.getStringMap(new String(json, UTF_8));
		if ( result == null ) {
			throw new IllegalArgumentException("Invalid result JSON");
		}
		return result;
	}

	private static Map<String, Object> copyMap(Map<?, ?> map, Redactor redactor, int depth) {
		checkDepth(depth);
		final Map<String, Object> result = new LinkedHashMap<>(Math.max(4, map.size() * 2));
		for ( Map.Entry<?, ?> e : map.entrySet() ) {
			final Object v = copyValue(e.getValue(), redactor, depth + 1);
			if ( v != null ) {
				result.put(String.valueOf(e.getKey()), v);
			}
		}
		return result;
	}

	private static List<@Nullable Object> copyList(Collection<?> list, Redactor redactor, int depth) {
		checkDepth(depth);
		final List<@Nullable Object> result = new ArrayList<>(list.size());
		for ( Object o : list ) {
			result.add(copyValue(o, redactor, depth + 1));
		}
		return result;
	}

	private static @Nullable Object copyValue(@Nullable Object value, Redactor redactor, int depth) {
		if ( value == null ) {
			return null;
		} else if ( value instanceof Number || value instanceof Boolean ) {
			return value;
		} else if ( value instanceof Map<?, ?> m ) {
			return copyMap(m, redactor, depth);
		} else if ( value instanceof Collection<?> c ) {
			return copyList(c, redactor, depth);
		} else if ( value instanceof Object[] a ) {
			return copyList(Arrays.asList(a), redactor, depth);
		} else if ( value instanceof Enum<?> e ) {
			return e.name();
		}
		// every other value, including String, is converted to redacted text
		return redactor.redact(value.toString());
	}

	private static void checkDepth(int depth) {
		if ( depth > MAX_DEPTH ) {
			throw new IllegalStateException("Result nested too deeply");
		}
	}

	@SuppressWarnings("unchecked")
	private static List<@Nullable Object> truncatedList(Map<String, Object> copy) {
		final Object t = copy.get(TRUNCATED_KEY);
		if ( t instanceof List<?> ) {
			// all lists in the copy are created by copyList()
			return (List<@Nullable Object>) t;
		}
		final List<@Nullable Object> result = new ArrayList<>(4);
		copy.put(TRUNCATED_KEY, result);
		return result;
	}

	private static String toJson(Object value) {
		final String json = JsonUtils.getJSONString(value, null);
		if ( json == null ) {
			throw new IllegalStateException("Unable to encode result");
		}
		return json;
	}

	private static String compress(String json) {
		final byte[] bytes = json.getBytes(UTF_8);
		final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, bytes.length / 4));
		// close the gzip stream (writing the trailer) before reading the bytes
		try (GZIPOutputStream gz = new BestCompressionGzipOutputStream(out)) {
			gz.write(bytes);
		} catch ( IOException e ) {
			throw new IllegalStateException("Unable to compress result", e);
		}
		return Base64.getEncoder().encodeToString(out.toByteArray());
	}

	/**
	 * Remove items from the tail of the longest list under {@code data}.
	 *
	 * @param copy
	 *        the envelope copy
	 * @param truncated
	 *        the truncated list to update
	 * @return {@code true} if a list was trimmed, {@code false} if no non-empty
	 *         list was found
	 */
	private static boolean trimLongestList(Map<String, Object> copy, List<@Nullable Object> truncated) {
		final ListRef longest = longestList(copy.get(DATA_KEY), DATA_KEY, null);
		if ( longest == null ) {
			return false;
		}
		final List<?> list = longest.list;
		final int size = list.size();
		final int drop = (size + 3) / 4; // max(1, ceil(n/4)) for n >= 1
		list.subList(size - drop, size).clear();
		recordTruncation(truncated, longest.path, size, list.size());
		return true;
	}

	private static @Nullable ListRef longestList(@Nullable Object node, String path,
			@Nullable ListRef best) {
		ListRef result = best;
		if ( node instanceof Map<?, ?> m ) {
			for ( Map.Entry<?, ?> e : m.entrySet() ) {
				result = longestList(e.getValue(), path + '.' + e.getKey(), result);
			}
		} else if ( node instanceof List<?> l ) {
			final int size = l.size();
			if ( size > 0 && (result == null || size > result.list.size()) ) {
				result = new ListRef(l, path);
			}
			for ( int i = 0; i < size; i++ ) {
				final Object o = l.get(i);
				if ( o instanceof Map<?, ?> || o instanceof List<?> ) {
					result = longestList(o, path + '[' + i + ']', result);
				}
			}
		}
		return result;
	}

	private static boolean dropData(Map<String, Object> copy, List<@Nullable Object> truncated) {
		final Object data = copy.get(DATA_KEY);
		if ( data instanceof Map<?, ?> m && m.isEmpty() ) {
			return false;
		}
		copy.put(DATA_KEY, new LinkedHashMap<String, Object>(2));
		recordTruncation(truncated, DATA_KEY, -1, 0);
		return true;
	}

	@SuppressWarnings("unchecked")
	private static void recordTruncation(List<@Nullable Object> truncated, String path, int total,
			int kept) {
		for ( Object o : truncated ) {
			if ( o instanceof Map<?, ?> m && path.equals(m.get("path")) ) {
				// all maps in the copy are created by copyMap(); keep the original total
				final Map<String, Object> entry = (Map<String, Object>) m;
				entry.put("kept", kept);
				entry.put("reason", BUDGET_REASON);
				return;
			}
		}
		final Map<String, Object> entry = new LinkedHashMap<>(8);
		entry.put("path", path);
		entry.put("total", total);
		entry.put("kept", kept);
		entry.put("reason", BUDGET_REASON);
		truncated.add(entry);
	}

	private static String fallback(Map<String, Object> copy, int budget) {
		final Map<String, Object> result = new LinkedHashMap<>(8);
		for ( String key : new String[] { "v", "op", "opV" } ) {
			final Object v = fallbackValue(copy.get(key));
			if ( v != null ) {
				result.put(key, v);
			}
		}
		result.put("error", BUDGET_REASON);
		final String encoded = compress(toJson(result));
		if ( encoded.length() > budget ) {
			throw new IllegalStateException("Result budget too small");
		}
		return encoded;
	}

	private static @Nullable Object fallbackValue(@Nullable Object value) {
		if ( value instanceof Number || value instanceof Boolean ) {
			return value;
		} else if ( value instanceof String s ) {
			// already redacted by copyValue()
			return Redactor.cut(s, MAX_FALLBACK_STRING_LENGTH);
		}
		return null;
	}

	private static final class ListRef {

		private final List<?> list;
		private final String path;

		private ListRef(List<?> list, String path) {
			super();
			this.list = list;
			this.path = path;
		}

	}

	private static final class BestCompressionGzipOutputStream extends GZIPOutputStream {

		private BestCompressionGzipOutputStream(OutputStream out) throws IOException {
			super(out);
			def.setLevel(Deflater.BEST_COMPRESSION);
		}

	}

}
