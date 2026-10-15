/* ==================================================================
 * PatternNormalizer.java - 16/10/2026 9:00:00 am
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

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Normalise already-redacted log messages into patterns that group similar
 * messages together.
 *
 * <p>
 * Normalisation proceeds as follows:
 * </p>
 *
 * <ol>
 * <li>A source ID is extracted from known SolarNode message templates only (for
 * example {@code Communication problem reading source X from Modbus device Y})
 * and replaced by {@code <source>}.</li>
 * <li>A target is extracted, being the first of an IPv4 {@code address:port}, a
 * {@code /dev/} serial device path, or a {@code host:port}, and replaced by
 * {@code <target>}.</li>
 * <li>UUIDs, IPv4 addresses, hex values, quoted strings and numbers are
 * replaced by placeholders.</li>
 * <li>Whitespace is collapsed and the result cut to {@link #MAX_LENGTH} code
 * points.</li>
 * </ol>
 *
 * <p>
 * All regular expressions are bounded and use possessive quantifiers so that
 * matching takes linear time.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public final class PatternNormalizer {

	/** The maximum pattern length, in code points. */
	public static final int MAX_LENGTH = 200;

	/** The source placeholder. */
	public static final String SOURCE_PLACEHOLDER = "<source>";

	/** The target placeholder. */
	public static final String TARGET_PLACEHOLDER = "<target>";

	private static final String SRC = "([A-Za-z0-9_./:~-]{1,64}?)";

	private static final String SRC_BRACKETED = "\\[([A-Za-z0-9_./:~-]{1,64}+)\\]";

	/**
	 * Known message templates that include a source ID, as group 1.
	 *
	 * <p>
	 * Each is matched from the start of the message.
	 * </p>
	 */
	// @formatter:off
	private static final List<Pattern> SOURCE_TEMPLATES = List.of(
			// ModbusDatumDataSource, MBusDeviceDatumDataSourceSupport, SunSpec, etc.
			Pattern.compile("Communication problem reading source " + SRC + " from "),
			// VirtualMeterDatumFilterService
			Pattern.compile("Communication error acquiring metadata for source " + SRC + ": "),
			Pattern.compile("Error accessing metadata for source " + SRC + ": "),
			// DatumDataSourceSupport
			Pattern.compile("Error saving source " + SRC + " metadata: "),
			Pattern.compile("Error saving metadata values .{0,300}? for source " + SRC_BRACKETED
					+ ": "),
			// LocationDatumDataSource
			Pattern.compile("Error looking up location [^ ]{1,64}+ for source " + SRC_BRACKETED
					+ ": "),
			// ResourceStorageServiceDirectoryWatcher
			Pattern.compile("No (?:DatumQueue|ResourceStorageService) available: "
					+ "cannot generate datum for source " + SRC + "$"),
			// TempestUdpDatumDataSource
			Pattern.compile("Error stopping Tempest UDP listener for source " + SRC_BRACKETED
					+ ": "),
			Pattern.compile("Invalid JSON encountered in Tempest UDP source " + SRC_BRACKETED
					+ ": "),
			// JsonDatumMetadataService
			Pattern.compile("Error generating cached metadata JSON for source " + SRC + ": "),
			// BacnetDatumDataSource
			Pattern.compile("Bacnet source " + SRC_BRACKETED + " device configuration ")
			);
	// @formatter:on

	private static final Pattern IPV4_PORT = Pattern
			.compile("(?<![0-9A-Za-z.])((?:[0-9]{1,3}+\\.){3}+[0-9]{1,3}+):([0-9]{1,5}+)(?![0-9])");

	private static final Pattern DEV_PATH = Pattern
			.compile("(?<![A-Za-z0-9_./-])/dev/[A-Za-z0-9_./-]{1,40}+(?![A-Za-z0-9_./-])");

	private static final Pattern HOST_PORT = Pattern
			.compile("(?<![A-Za-z0-9.-])([A-Za-z0-9.-]{1,253}+):([0-9]{1,5}+)(?![0-9])");

	private static final Pattern UUID = Pattern
			.compile("(?<![0-9A-Fa-f-])[0-9A-Fa-f]{8}+-[0-9A-Fa-f]{4}+-[0-9A-Fa-f]{4}+"
					+ "-[0-9A-Fa-f]{4}+-[0-9A-Fa-f]{12}+(?![0-9A-Fa-f-])");

	private static final Pattern IPV4 = Pattern
			.compile("(?<![0-9A-Za-z.])(?:[0-9]{1,3}+\\.){3}+[0-9]{1,3}+(?![0-9]|\\.[0-9])");

	private static final Pattern HEX = Pattern
			.compile("(?<![A-Za-z0-9_])(?:0[xX][0-9A-Fa-f]++|[0-9A-Fa-f]{8,}+)(?![A-Za-z0-9_])");

	private static final Pattern QUOTED = Pattern.compile("\"[^\"\\r\\n]{0,200}+\"");

	private static final Pattern NUMBER = Pattern.compile("[0-9]++(?:\\.[0-9]++)*+");

	private static final Pattern WHITESPACE = Pattern.compile("\\s++");

	/**
	 * A normalised message.
	 */
	public static final class Normalized {

		private final String pattern;
		private final @Nullable String target;
		private final @Nullable String source;

		/**
		 * Constructor.
		 *
		 * @param pattern
		 *        the pattern
		 * @param target
		 *        the target, or {@code null}
		 * @param source
		 *        the source ID, or {@code null}
		 */
		public Normalized(String pattern, @Nullable String target, @Nullable String source) {
			super();
			this.pattern = pattern;
			this.target = target;
			this.source = source;
		}

		/**
		 * Get the pattern.
		 *
		 * @return the pattern, never {@code null}
		 */
		public String getPattern() {
			return pattern;
		}

		/**
		 * Get the target.
		 *
		 * @return the target, for example {@code 192.168.1.2:502} or
		 *         {@code /dev/ttyUSB0}, or {@code null} if none extracted
		 */
		public @Nullable String getTarget() {
			return target;
		}

		/**
		 * Get the source ID.
		 *
		 * @return the source ID, or {@code null} if none extracted
		 */
		public @Nullable String getSource() {
			return source;
		}

	}

	private PatternNormalizer() {
		// not available
	}

	/**
	 * Normalise a message.
	 *
	 * @param redacted
	 *        the message, which must already have been redacted
	 * @return the normalised result, never {@code null}
	 */
	public static Normalized normalize(String redacted) {
		String s = redacted;

		// source, from known templates only
		String source = null;
		for ( Pattern p : SOURCE_TEMPLATES ) {
			Matcher m = p.matcher(s);
			if ( m.lookingAt() ) {
				String src = m.group(1);
				if ( LogsQuery.SOURCE_ID.matcher(src).matches() ) {
					source = src;
					s = s.substring(0, m.start(1)) + SOURCE_PLACEHOLDER + s.substring(m.end(1));
				}
				break;
			}
		}

		// target
		String target = findTarget(s);
		if ( target != null ) {
			s = s.replace(target, TARGET_PLACEHOLDER);
		}

		// generic placeholders
		s = UUID.matcher(s).replaceAll("<uuid>");
		s = IPV4.matcher(s).replaceAll("<ip>");
		s = HEX.matcher(s).replaceAll(r -> Matcher.quoteReplacement(hex(r.group())));
		s = QUOTED.matcher(s).replaceAll("\"<s>\"");
		s = NUMBER.matcher(s).replaceAll("<n>");
		s = WHITESPACE.matcher(s).replaceAll(" ").trim();
		return new Normalized(Redactor.cut(s, MAX_LENGTH), target, source);
	}

	private static String hex(String s) {
		if ( s.length() > 1 && (s.charAt(1) == 'x' || s.charAt(1) == 'X') ) {
			return "<hex>";
		}
		boolean digit = false;
		boolean letter = false;
		for ( int i = 0, len = s.length(); i < len; i++ ) {
			char c = s.charAt(i);
			if ( c >= '0' && c <= '9' ) {
				digit = true;
			} else {
				letter = true;
			}
		}
		return (digit && letter ? "<hex>" : s);
	}

	private static @Nullable String findTarget(String s) {
		Matcher m = IPV4_PORT.matcher(s);
		while ( m.find() ) {
			if ( validIpv4(m.group(1)) && validPort(m.group(2)) ) {
				return m.group();
			}
		}
		m = DEV_PATH.matcher(s);
		while ( m.find() ) {
			String dev = m.group();
			int end = dev.length();
			while ( end > 0 && (dev.charAt(end - 1) == '.' || dev.charAt(end - 1) == '/') ) {
				end--;
			}
			dev = dev.substring(0, end);
			if ( dev.length() > 5 && !dev.contains("..") ) {
				return dev;
			}
		}
		m = HOST_PORT.matcher(s);
		while ( m.find() ) {
			if ( validHost(m.group(1)) && validPort(m.group(2)) ) {
				return m.group();
			}
		}
		return null;
	}

	private static boolean validIpv4(String s) {
		for ( String octet : s.split("\\.", 4) ) {
			if ( Integer.parseInt(octet) > 255 ) {
				return false;
			}
		}
		return true;
	}

	private static boolean validPort(String s) {
		int port = Integer.parseInt(s);
		return port > 0 && port <= 65535;
	}

	private static boolean validHost(String host) {
		if ( "localhost".equalsIgnoreCase(host) ) {
			return true;
		}
		if ( host.indexOf('.') < 0 ) {
			return false;
		}
		final String[] labels = host.split("\\.", -1);
		boolean letter = false;
		for ( String label : labels ) {
			if ( label.isEmpty() || label.length() > 63 || label.charAt(0) == '-'
					|| label.charAt(label.length() - 1) == '-' ) {
				return false;
			}
			for ( int i = 0, len = label.length(); i < len && !letter; i++ ) {
				letter = Character.isLetter(label.charAt(i));
			}
		}
		// exclude source file references like Foo.java:123
		final String tld = labels[labels.length - 1].toLowerCase(Locale.ENGLISH);
		return letter && !("java".equals(tld) || "kt".equals(tld) || "groovy".equals(tld));
	}

}
