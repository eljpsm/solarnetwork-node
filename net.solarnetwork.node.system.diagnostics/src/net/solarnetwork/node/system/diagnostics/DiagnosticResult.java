/* ==================================================================
 * DiagnosticResult.java - 16/10/2026 9:00:00 am
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The result of a {@link DiagnosticOp}.
 *
 * <p>
 * All values added to the result must be JSON-friendly: {@link String},
 * {@link Number}, {@link Boolean}, {@link List}, or {@link Map} with
 * {@link String} keys, recursively.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public final class DiagnosticResult {

	/**
	 * A reason for an item not being available.
	 */
	public enum Reason {

		/** Access was denied. */
		DENIED,

		/** The item does not exist. */
		ABSENT,

		/** The deadline expired before the item could be collected. */
		TIMEOUT,

		/** The item is not supported on this node. */
		UNSUPPORTED,

		/** An error occurred collecting the item. */
		ERROR,

		;

		/**
		 * Get the key value.
		 *
		 * @return the lower-case key value
		 */
		public String key() {
			return name().toLowerCase(Locale.ENGLISH);
		}

	}

	private final Map<String, Object> data = new LinkedHashMap<>(8);
	private final List<Map<String, Object>> notAvailable = new ArrayList<>(2);
	private final List<Map<String, Object>> truncated = new ArrayList<>(2);

	/**
	 * Constructor.
	 */
	public DiagnosticResult() {
		super();
	}

	/**
	 * Add a data value.
	 *
	 * @param key
	 *        the key
	 * @param value
	 *        the value; if {@code null} then nothing is added
	 * @return this instance
	 */
	public DiagnosticResult put(String key, @Nullable Object value) {
		if ( value != null ) {
			data.put(key, value);
		}
		return this;
	}

	/**
	 * Record an item that is not available.
	 *
	 * <p>
	 * Fixed sub-items are separated by {@code /}, for example
	 * {@code logs/scan}. Dynamic qualifiers such as paths or file names are
	 * separated by {@code :}, for example {@code mounts:/run}, so that the
	 * qualifier is not joined with the item name into one token-like run that
	 * the {@link Redactor} would mask.
	 * </p>
	 *
	 * @param item
	 *        the item, for example {@code memory} or {@code mounts:/run}
	 * @param reason
	 *        the reason
	 * @return this instance
	 */
	public DiagnosticResult notAvailable(String item, Reason reason) {
		Map<String, Object> m = new LinkedHashMap<>(2);
		m.put("item", item);
		m.put("reason", reason.key());
		notAvailable.add(m);
		return this;
	}

	/**
	 * Record a truncated list.
	 *
	 * @param path
	 *        the dotted path of the truncated list, for example
	 *        {@code data.summaries}
	 * @param total
	 *        the total number of items available
	 * @param kept
	 *        the number of items kept
	 * @param reason
	 *        the reason, for example {@code budget}, {@code deadline},
	 *        {@code maxBytes}, or {@code maxKeys}
	 * @return this instance
	 */
	public DiagnosticResult truncated(String path, int total, int kept, String reason) {
		Map<String, Object> m = new LinkedHashMap<>(4);
		m.put("path", path);
		m.put("total", total);
		m.put("kept", kept);
		m.put("reason", reason);
		truncated.add(m);
		return this;
	}

	/**
	 * Get the data.
	 *
	 * @return the data map, never {@code null}
	 */
	public Map<String, Object> getData() {
		return data;
	}

	/**
	 * Get the items that are not available.
	 *
	 * @return the list, never {@code null}
	 */
	public List<Map<String, Object>> getNotAvailable() {
		return notAvailable;
	}

	/**
	 * Get the truncated list information.
	 *
	 * @return the list, never {@code null}
	 */
	public List<Map<String, Object>> getTruncated() {
		return truncated;
	}

}
