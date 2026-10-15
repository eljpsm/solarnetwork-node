/* ==================================================================
 * Redactor.java - 16/10/2026 9:00:00 am
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

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Redact secrets from free text, such as a log message.
 *
 * <p>
 * The following rules are applied, in order:
 * </p>
 *
 * <ol>
 * <li>Control and bidirectional formatting characters are removed, with
 * carriage return, line feed and tab replaced by a space.</li>
 * <li>Known leaking message templates are masked: the parameter map after
 * {@code received with parameters:}, the instruction JSON after
 * {@code Unable to accept instruction JSON}, everything after
 * {@code Error posting instruction status} (and, outside of any braces,
 * {@code resultParameters=}), and the value after
 * {@code SQL warning saving setting ... to}, and everything after the
 * {@code wpa_supplicant} {@code Invalid passphrase length}.</li>
 * <li>The content of every <code>{...}</code> group is elided to
 * <code>{…}</code>, unless the content is only a network endpoint: an IPv4
 * address or host name with a port (for example <code>{192.168.1.2:502}</code>
 * as logged by Modbus TCP networks), or a {@code /dev/} serial device path,
 * optionally followed by a {@code readTimeout} value.</li>
 * <li>URL user info is masked, as are URL query and fragment components. Bare
 * {@code ?k=v} query strings are masked to the end of the text, and bare
 * {@code &k=v} values are masked.</li>
 * <li>Authorization values are masked: {@code Authorization:}, {@code Bearer},
 * {@code Basic}, {@code SNWS2} and {@code SolarNetworkWS} credentials, and
 * everything after {@code -----BEGIN}.</li>
 * <li>Values of sensitive keys (see {@link #isSensitiveKey(String)}) are
 * masked, in the {@code k=v}, {@code k: v}, {@code "k":"v"} and {@code k="v"}
 * forms, as is a {@code value} whose sibling {@code name}, {@code key} or
 * {@code type} names a sensitive key.</li>
 * <li>A credential-like word (at least 12 token characters, mixing letters and
 * digits) that directly follows a bare sensitive keyword such as {@code token}
 * or {@code password} and a space is masked.</li>
 * <li>AWS access key IDs ({@code AKIA} or {@code ASIA} followed by 16
 * upper-case letters or digits) are masked.</li>
 * <li>Runs of at least 32 token characters ({@code [A-Za-z0-9+/=_-]}) that
 * contain a digit, {@code +}, {@code /} or trailing {@code =} padding are
 * replaced by {@link #TOKEN_MASK}.</li>
 * </ol>
 *
 * <p>
 * All rules are prefix-safe: any truncated construct (an unclosed brace,
 * bracket, quote, query string, or partial URL user info) is masked through to
 * the end of the text, so redacting a truncated message never exposes more than
 * redacting the whole message, except that a long token-like run cut short may
 * no longer be recognised: callers that truncate text before redacting it must
 * drop any trailing token-like run first (see
 * {@link #trimPartialToken(String)}). Redaction is idempotent and runs in
 * linear time, using only hand-written scanners and simple bounded regular
 * expressions.
 * </p>
 *
 * <p>
 * This class is not thread-safe.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public final class Redactor {

	/** The mask used in place of redacted values. */
	public static final String MASK = "<redacted>";

	/** The mask used in place of long, token-like character runs. */
	public static final String TOKEN_MASK = "<tok>";

	/** The ellipsis used for elided brace content. */
	public static final String ELLIPSIS = "…";

	/** The minimum length of a token-like run to mask. */
	public static final int LONG_TOKEN_LENGTH = 32;

	/** The minimum length of a credential-like word after a bare keyword. */
	public static final int BARE_CREDENTIAL_LENGTH = 12;

	/** The pattern of sensitive key names, matched anywhere in a key. */
	private static final Pattern SENSITIVE_KEY = Pattern.compile("token|secret|passw(?:or)?d|pwd"
			+ "|api[_-]?key|appid|app[_-]?key|access[_-]?key|private[_-]?key|client[_-]?secret"
			+ "|refresh[_-]?token|credentials?|signature|session[_-]?id|passphrase|psk"
			+ "|authorization|bearer|cookie|set-cookie", Pattern.CASE_INSENSITIVE);

	/** Keys whose value can name a sensitive sibling {@code value}. */
	private static final Set<String> SIBLING_NAME_KEYS = Set.of("name", "key", "type", "property",
			"setting", "param", "parameter", "field");

	/** Bare keywords that can precede a credential, separated by a space. */
	private static final Set<String> BARE_KEYWORDS = Set.of("token", "secret", "password", "passwd",
			"pwd", "apikey", "api-key", "api_key", "passphrase", "psk", "credential", "credentials");

	/**
	 * Brace content that is only a network endpoint, for example as logged by
	 * Modbus networks like {@code 1@NiftyTcpModbusNetwork{192.168.1.2:502}}.
	 */
	private static final Pattern NETWORK_ENDPOINT = Pattern
			.compile("(?:(?:[0-9]{1,3}+\\.){3}+[0-9]{1,3}+|localhost"
					+ "|[A-Za-z0-9-]{1,63}+(?:\\.[A-Za-z0-9-]{1,63}+){1,8}+):[0-9]{1,5}+"
					+ "|/dev/[A-Za-z0-9_./-]{1,40}+(?: readTimeout=[0-9]{1,9}+)?+");

	private static final int MAX_NETWORK_ENDPOINT = 300;
	private static final String[] AWS_KEY_PREFIXES = new String[] { "AKIA", "ASIA" };
	private static final int AWS_KEY_LENGTH = 20;
	private static final int MIN_PARTIAL_SECRET = 5;

	private static final String SIBLING_VALUE_KEY = "value";
	private static final String AMZ_PREFIX = "x-amz-";

	private static final String PARAMS_MARKER = "received with parameters:";
	private static final String JSON_MARKER = "Unable to accept instruction JSON";
	private static final String STATUS_MARKER = "Error posting instruction status";
	private static final String RESULT_PARAMS_MARKER = "resultParameters=";
	private static final String SQL_SETTING_MARKER = "SQL warning saving setting ";
	private static final String SQL_SETTING_VALUE_MARKER = " to ";
	private static final String WPA_PASSPHRASE_MARKER = "Invalid passphrase length";
	private static final String PEM_MARKER = "-----BEGIN";
	private static final String AUTHORIZATION = "authorization";
	private static final String BEARER = "bearer ";
	private static final String BASIC = "Basic ";
	private static final String[] SIGNED_AUTH_SCHEMES = new String[] { "SNWS2 ", "SolarNetworkWS " };

	private static final int MAX_PASSES = 3;
	private static final int MAX_QUOTED_KEY = 128;
	private static final int MAX_SIBLING_PROBE = 256;
	private static final int SIBLING_DISTANCE = 512;
	private static final int MAX_KEPT_SUFFIX = 3;
	private static final int MIN_BASIC_LENGTH = 6;
	private static final int MAX_PORT_DIGITS = 5;

	private int count;

	/**
	 * Constructor.
	 */
	public Redactor() {
		super();
	}

	/**
	 * Redact secrets from text.
	 *
	 * @param text
	 *        the text to redact
	 * @return the redacted text, never {@code null}
	 */
	public String redact(String text) {
		String s = stripControls(text);
		for ( int i = 0; i < MAX_PASSES; i++ ) {
			String r = redactPass(s);
			if ( r.equals(s) ) {
				break;
			}
			s = r;
		}
		return s;
	}

	/**
	 * Get the number of values redacted so far by this instance.
	 *
	 * @return the count
	 */
	public int count() {
		return count;
	}

	/**
	 * Cut text to a maximum number of code points.
	 *
	 * <p>
	 * Only call this on text that has already been redacted.
	 * </p>
	 *
	 * @param text
	 *        the text
	 * @param maxCodePoints
	 *        the maximum number of code points
	 * @return the text, cut at a code point boundary if needed
	 */
	public static String cut(String text, int maxCodePoints) {
		if ( maxCodePoints <= 0 ) {
			return "";
		}
		if ( text.codePointCount(0, text.length()) <= maxCodePoints ) {
			return text;
		}
		return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
	}

	/**
	 * Remove a trailing token-like character run from text that was cut before
	 * being redacted.
	 *
	 * <p>
	 * A long token cut short may be shorter than {@link #LONG_TOKEN_LENGTH} and
	 * so no longer recognised by {@link #redact(String)}. Text that was cut at
	 * an arbitrary point before redaction must have that partial run removed.
	 * </p>
	 *
	 * @param text
	 *        the cut text
	 * @return the text without any trailing token-like characters
	 */
	public static String trimPartialToken(String text) {
		int end = text.length();
		while ( end > 0 && isTokenChar(text.charAt(end - 1)) ) {
			end--;
		}
		return (end < text.length() ? text.substring(0, end) : text);
	}

	/**
	 * Test if a key name is considered sensitive.
	 *
	 * <p>
	 * A key is sensitive if it contains (ignoring case) any of {@code token},
	 * {@code secret}, {@code password}, {@code passwd}, {@code pwd},
	 * {@code apikey}, {@code appid}, {@code appkey}, {@code accesskey},
	 * {@code privatekey}, {@code clientsecret}, {@code credential},
	 * {@code signature}, {@code sessionid}, {@code passphrase}, {@code psk},
	 * {@code authorization}, {@code bearer} or {@code cookie} (where the
	 * compound words may be separated by {@code _} or {@code -}), or if it
	 * starts with {@code X-Amz-}.
	 * </p>
	 *
	 * @param key
	 *        the key to test
	 * @return {@code true} if the key is sensitive
	 */
	public static boolean isSensitiveKey(String key) {
		return key.regionMatches(true, 0, AMZ_PREFIX, 0, AMZ_PREFIX.length())
				|| SENSITIVE_KEY.matcher(key).find();
	}

	private String redactPass(String text) {
		String s = templates(text);
		s = elideBraces(s);
		s = maskAfter(s, RESULT_PARAMS_MARKER, "");
		s = urls(s);
		s = bareQueries(s);
		s = auth(s);
		s = keys(s);
		s = bareKeywords(s);
		s = awsKeys(s);
		return longTokens(s);
	}

	/*
	 * =========================================================================
	 * === Control characters
	 * =========================================================================
	 * ===
	 */

	private static String stripControls(String s) {
		StringBuilder buf = null;
		for ( int i = 0, n = s.length(); i < n; i++ ) {
			final char c = s.charAt(i);
			final boolean space = (c == '\r' || c == '\n' || c == '\t');
			if ( !space && !isControlOrBidi(c) ) {
				if ( buf != null ) {
					buf.append(c);
				}
				continue;
			}
			if ( buf == null ) {
				buf = new StringBuilder(n);
				buf.append(s, 0, i);
			}
			if ( space ) {
				buf.append(' ');
			}
		}
		return (buf != null ? buf.toString() : s);
	}

	private static boolean isControlOrBidi(char c) {
		return c < 0x20 || (c >= 0x7F && c <= 0x9F) || (c >= 0x202A && c <= 0x202E)
				|| (c >= 0x2066 && c <= 0x2069) || c == 0x200E || c == 0x200F || c == 0x061C;
	}

	/*
	 * =========================================================================
	 * === Templates
	 * =========================================================================
	 * ===
	 */

	private String templates(String text) {
		String s = maskAfter(text, PARAMS_MARKER, " ");
		s = instructionJson(s);
		s = maskAfter(s, STATUS_MARKER, " ");
		s = maskAfter(s, WPA_PASSPHRASE_MARKER, " ");
		return sqlSetting(s);
	}

	private String maskAfter(String s, String marker, String separator) {
		final int idx = s.indexOf(marker);
		if ( idx < 0 ) {
			return s;
		}
		return replaceTail(s, idx + marker.length(), separator + MASK);
	}

	private String replaceTail(String s, int start, String canonical) {
		if ( isBlank(s, start, s.length())
				|| (s.length() - start == canonical.length() && s.startsWith(canonical, start)) ) {
			return s;
		}
		count++;
		return s.substring(0, start) + canonical;
	}

	private String instructionJson(String s) {
		final int idx = s.indexOf(JSON_MARKER);
		if ( idx < 0 ) {
			return s;
		}
		final int start = idx + JSON_MARKER.length();
		String canonical = " " + MASK;
		final int sep = s.lastIndexOf("]: ");
		if ( sep >= start ) {
			// the logged exception follows the JSON: keep only its class name
			final int fs = sep + 3;
			int fe = fs;
			while ( fe < s.length() && isClassNameChar(s.charAt(fe)) ) {
				fe++;
			}
			final String fqcn = s.substring(fs, fe);
			if ( (fe == s.length() || s.charAt(fe) == ':') && isExceptionClassName(fqcn) ) {
				canonical = " [" + MASK + "]: " + fqcn + (fe < s.length() ? ": " + MASK : "");
			}
		}
		return replaceTail(s, start, canonical);
	}

	private static boolean isExceptionClassName(String name) {
		return name.indexOf('.') > 0 && !name.endsWith(".")
				&& (name.endsWith("Exception") || name.endsWith("Error"));
	}

	private String sqlSetting(String s) {
		final int idx = s.indexOf(SQL_SETTING_MARKER);
		if ( idx < 0 ) {
			return s;
		}
		final int to = s.indexOf(SQL_SETTING_VALUE_MARKER, idx + SQL_SETTING_MARKER.length());
		if ( to < 0 ) {
			return s;
		}
		return replaceTail(s, to + SQL_SETTING_VALUE_MARKER.length(), MASK);
	}

	/*
	 * =========================================================================
	 * === Braces
	 * =========================================================================
	 * ===
	 */

	private String elideBraces(String s) {
		int i = s.indexOf('{');
		if ( i < 0 ) {
			return s;
		}
		final int n = s.length();
		final StringBuilder buf = new StringBuilder(n);
		buf.append(s, 0, i);
		while ( i < n ) {
			final char c = s.charAt(i);
			if ( c != '{' ) {
				buf.append(c);
				i++;
				continue;
			}
			// linear scan for the matching close brace, ignoring braces in quotes
			int depth = 0;
			boolean quoted = false;
			int close = -1;
			for ( int j = i; j < n; j++ ) {
				final char d = s.charAt(j);
				if ( quoted ) {
					if ( d == '\\' ) {
						j++;
					} else if ( d == '"' ) {
						quoted = false;
					}
				} else if ( d == '"' ) {
					quoted = true;
				} else if ( d == '{' ) {
					depth++;
				} else if ( d == '}' && --depth == 0 ) {
					close = j;
					break;
				}
			}
			final int contentEnd = (close < 0 ? n : close);
			buf.append('{');
			if ( close > 0 && isNetworkEndpoint(s, i + 1, close) ) {
				buf.append(s, i + 1, close).append('}');
				i = close + 1;
				continue;
			}
			if ( contentEnd > i + 1 ) {
				buf.append(ELLIPSIS);
				if ( !(contentEnd - i - 1 == ELLIPSIS.length() && s.startsWith(ELLIPSIS, i + 1)) ) {
					count++;
				}
			}
			if ( close < 0 ) {
				break;
			}
			buf.append('}');
			i = close + 1;
		}
		return buf.toString();
	}

	private static boolean isNetworkEndpoint(String s, int from, int to) {
		return (to - from <= MAX_NETWORK_ENDPOINT
				&& NETWORK_ENDPOINT.matcher(s).region(from, to).matches());
	}

	/*
	 * =========================================================================
	 * === URLs and query strings
	 * =========================================================================
	 * ===
	 */

	private String urls(String s) {
		int idx = s.indexOf("://");
		if ( idx < 0 ) {
			return s;
		}
		final int n = s.length();
		StringBuilder buf = null;
		int copied = 0;
		while ( idx >= 0 ) {
			final int a = idx + 3;
			final boolean ipv6 = (a < n && s.charAt(a) == '[');
			int e = a;
			int at = -1;
			int colon = -1;
			boolean ipv6End = false;
			char c;
			while ( e < n && !isAuthorityEnd(c = s.charAt(e)) ) {
				if ( c == '@' ) {
					at = e;
				} else if ( c == ']' ) {
					ipv6End = true;
				} else if ( c == ':' && colon < 0 && (!ipv6 || ipv6End) ) {
					colon = e;
				}
				e++;
			}
			if ( at > a ) {
				if ( !isMask(s, a, at) ) {
					buf = append(buf, s, copied, a).append(MASK);
					copied = at;
					count++;
				}
			} else if ( at < 0 && colon >= 0 && !isPortLike(s, colon + 1, e)
					&& !isMask(s, colon + 1, e) ) {
				// possibly truncated user info
				buf = append(buf, s, copied, colon + 1).append(MASK);
				copied = e;
				count++;
			}
			int p = e;
			while ( p < n && (c = s.charAt(p)) != '?' && c != '#' && !isWhitespace(c) && c != '"'
					&& c != '\'' ) {
				p++;
			}
			int next = p;
			if ( p < n && (s.charAt(p) == '?' || s.charAt(p) == '#') ) {
				int qe = p + 1;
				while ( qe < n && !isWhitespace(s.charAt(qe)) ) {
					qe++;
				}
				int ce = qe;
				for ( int k = 0; k < MAX_KEPT_SUFFIX && ce > p + 1
						&& isUrlSuffix(s.charAt(ce - 1)); k++ ) {
					ce--;
				}
				if ( ce > p + 1 && !isMask(s, p + 1, ce) ) {
					buf = append(buf, s, copied, p + 1).append(MASK);
					copied = ce;
					count++;
				}
				next = qe;
			}
			idx = s.indexOf("://", Math.max(next, a));
		}
		return finish(buf, s, copied);
	}

	private static boolean isAuthorityEnd(char c) {
		switch (c) {
			case '/':
			case '?':
			case '#':
			case '"':
			case '\'':
			case '\\':
			case '{':
			case '}':
			case '|':
			case '^':
			case '`':
				return true;
			default:
				return isWhitespace(c);
		}
	}

	private static boolean isUrlSuffix(char c) {
		switch (c) {
			case ']':
			case ')':
			case '}':
			case ',':
			case ';':
			case ':':
			case '.':
			case '"':
			case '\'':
				return true;
			default:
				return false;
		}
	}

	private static boolean isPortLike(String s, int from, int to) {
		int end = to;
		for ( int k = 0; k < MAX_KEPT_SUFFIX && end > from; k++ ) {
			final char c = s.charAt(end - 1);
			if ( c == '.' || c == ',' || c == ';' || c == ':' || c == ')' || c == ']' ) {
				end--;
			} else {
				break;
			}
		}
		if ( end - from > MAX_PORT_DIGITS ) {
			return false;
		}
		for ( int i = from; i < end; i++ ) {
			if ( !isDigit(s.charAt(i)) ) {
				return false;
			}
		}
		return true;
	}

	private String bareQueries(String s) {
		final int n = s.length();
		StringBuilder buf = null;
		int copied = 0;
		int i = 0;
		while ( i < n ) {
			final char c = s.charAt(i);
			if ( c != '?' && c != '&' ) {
				i++;
				continue;
			}
			int k = i + 1;
			while ( k < n && isQueryKeyChar(s.charAt(k)) ) {
				k++;
			}
			if ( k == i + 1 || k >= n || s.charAt(k) != '=' ) {
				i = k;
				continue;
			}
			final int start;
			int end;
			if ( c == '?' ) {
				// values may contain spaces: mask to the end
				start = i + 1;
				end = n;
			} else {
				start = k + 1;
				end = start;
				while ( end < n && s.charAt(end) != '&' && !isWhitespace(s.charAt(end)) ) {
					end++;
				}
			}
			if ( end > start && !isMask(s, start, end) ) {
				buf = append(buf, s, copied, start).append(MASK);
				copied = end;
				count++;
			}
			i = Math.max(end, k + 1);
		}
		return finish(buf, s, copied);
	}

	/*
	 * =========================================================================
	 * === Authorization
	 * =========================================================================
	 * ===
	 */

	private String auth(String text) {
		final String s = maskAfter(text, PEM_MARKER, " ");
		final int n = s.length();
		StringBuilder buf = null;
		int copied = 0;
		int i = 0;
		while ( i < n ) {
			final char c = s.charAt(i);
			int vs = -1;
			int ve = -1;
			if ( (c == 'A' || c == 'a') && isWordStart(s, i)
					&& s.regionMatches(true, i, AUTHORIZATION, 0, AUTHORIZATION.length()) ) {
				final int k = skipSpaces(s, i + AUTHORIZATION.length());
				if ( k < n && (s.charAt(k) == ':' || s.charAt(k) == '=') ) {
					vs = skipSpaces(s, k + 1);
					ve = s.indexOf(';', vs);
					if ( ve < 0 ) {
						ve = n;
					}
				}
			} else if ( (c == 'B' || c == 'b') && isWordStart(s, i)
					&& s.regionMatches(true, i, BEARER, 0, BEARER.length()) ) {
				vs = skipSpaces(s, i + BEARER.length());
				ve = vs;
				char d;
				while ( ve < n && !isWhitespace(d = s.charAt(ve)) && d != ',' && d != ';' && d != '"'
						&& d != '\'' ) {
					ve++;
				}
			} else if ( c == 'B' && isWordStart(s, i) && s.startsWith(BASIC, i) ) {
				vs = i + BASIC.length();
				ve = vs;
				while ( ve < n && isBase64Char(s.charAt(ve)) ) {
					ve++;
				}
				if ( ve - vs < MIN_BASIC_LENGTH && ve < n ) {
					// a short word, not at the end so not truncated
					vs = -1;
				}
			} else if ( c == 'S' && isWordStart(s, i) ) {
				for ( String scheme : SIGNED_AUTH_SCHEMES ) {
					if ( s.startsWith(scheme, i) ) {
						vs = i + scheme.length();
						ve = vs;
						while ( ve < n && !isWhitespace(s.charAt(ve)) ) {
							ve++;
						}
						break;
					}
				}
			}
			if ( vs >= 0 && ve > vs ) {
				if ( !isMask(s, vs, ve) ) {
					buf = append(buf, s, copied, vs).append(MASK);
					copied = ve;
					count++;
				}
				i = ve;
			} else {
				i++;
			}
		}
		return finish(buf, s, copied);
	}

	/*
	 * =========================================================================
	 * === Keys
	 * =========================================================================
	 * ===
	 */

	private String keys(String s) {
		final int n = s.length();
		StringBuilder buf = null;
		int copied = 0;
		int pending = -1;
		int i = 0;
		while ( i < n ) {
			final char c = s.charAt(i);
			final int ks;
			final int ke;
			final int after;
			if ( c == '"' ) {
				final int lim = Math.min(n, i + 1 + MAX_QUOTED_KEY);
				int j = i + 1;
				while ( j < lim && s.charAt(j) != '"' ) {
					j++;
				}
				if ( j >= lim ) {
					i++;
					continue;
				}
				ks = i + 1;
				ke = j;
				after = j + 1;
			} else if ( isKeyChar(c) && (i == 0 || !isKeyChar(s.charAt(i - 1))) ) {
				int j = i + 1;
				while ( j < n && isKeyChar(s.charAt(j)) ) {
					j++;
				}
				ks = i;
				ke = j;
				after = j;
			} else {
				i++;
				continue;
			}
			final int k = skipSpaces(s, after);
			if ( ke == ks || !isKeySeparator(s, k) ) {
				i = after;
				continue;
			}
			final String key = s.substring(ks, ke);
			final int v = skipSpaces(s, k + 1);
			if ( isSensitiveKey(key) || (pending >= 0 && i - pending <= SIBLING_DISTANCE
					&& key.equalsIgnoreCase(SIBLING_VALUE_KEY)) ) {
				pending = -1;
				final int[] ext = valueExtent(s, v, n);
				if ( ext[1] > ext[0] && !isMask(s, ext[0], ext[1]) ) {
					buf = append(buf, s, copied, ext[0]).append(MASK);
					copied = ext[1];
					count++;
				}
				i = Math.max(ext[2], k + 1);
			} else {
				if ( SIBLING_NAME_KEYS.contains(key.toLowerCase(Locale.ROOT)) ) {
					final int[] ext = valueExtent(s, v, Math.min(n, v + MAX_SIBLING_PROBE));
					if ( ext[1] > ext[0] && isSensitiveKey(s.substring(ext[0], ext[1])) ) {
						pending = i;
					}
				}
				i = k + 1;
			}
		}
		return finish(buf, s, copied);
	}

	private static boolean isKeySeparator(String s, int k) {
		if ( k >= s.length() ) {
			return false;
		}
		final char c = s.charAt(k);
		return c == '=' || (c == ':' && !s.startsWith("//", k + 1));
	}

	/**
	 * Find the extent of a value.
	 *
	 * @param s
	 *        the text
	 * @param v
	 *        the value start index
	 * @param limit
	 *        the maximum index to scan to
	 * @return the value content start, content end, and the index to resume
	 *         scanning from
	 */
	private static int[] valueExtent(String s, int v, int limit) {
		if ( v >= limit ) {
			return new int[] { v, v, v };
		}
		final char c = s.charAt(v);
		if ( c == '"' || c == '\'' ) {
			int j = v + 1;
			while ( j < limit ) {
				final char d = s.charAt(j);
				if ( d == '\\' ) {
					j += 2;
					continue;
				}
				if ( d == c ) {
					break;
				}
				j++;
			}
			if ( j >= limit ) {
				return new int[] { v + 1, limit, limit };
			}
			return new int[] { v + 1, j, j + 1 };
		} else if ( c == '[' ) {
			int j = v + 1;
			while ( j < limit && s.charAt(j) != ']' ) {
				j++;
			}
			if ( j >= limit ) {
				return new int[] { v + 1, limit, limit };
			}
			return new int[] { v + 1, j, j + 1 };
		} else if ( c == '{' ) {
			// already elided
			return new int[] { v, v, v };
		} else if ( s.startsWith(MASK, v) ) {
			final int e = v + MASK.length();
			return new int[] { v, e, e };
		}
		// unquoted: up to a delimiter that is followed by another key
		int j = v;
		while ( j < limit ) {
			if ( !isValueDelim(s.charAt(j)) ) {
				j++;
				continue;
			}
			int m = j;
			boolean hard = false;
			char d;
			while ( m < limit && isValueDelim(d = s.charAt(m)) ) {
				if ( d == ',' || d == ';' || d == '&' ) {
					hard = true;
				}
				m++;
			}
			if ( m >= limit ) {
				j = limit;
				break;
			}
			int kk = m;
			while ( kk < limit && isKeyChar(s.charAt(kk)) ) {
				kk++;
			}
			if ( kk > m ) {
				final int p = skipSpaces(s, kk);
				if ( p < s.length() && (s.charAt(p) == '='
						|| (hard && s.charAt(p) == ':' && !s.startsWith("//", p + 1))) ) {
					break;
				}
			}
			j = m;
		}
		return new int[] { v, j, j };
	}

	private static boolean isValueDelim(char c) {
		return c == ',' || c == ';' || c == '&' || c == ')' || c == ']' || isWhitespace(c);
	}

	/*
	 * =========================================================================
	 * === Bare keywords and AWS keys
	 * =========================================================================
	 * ===
	 */

	private String bareKeywords(String s) {
		final int n = s.length();
		StringBuilder buf = null;
		int copied = 0;
		int i = 0;
		while ( i < n ) {
			final char c = s.charAt(i);
			if ( !isAsciiAlnum(c) || !isWordStart(s, i) ) {
				i++;
				continue;
			}
			int j = i + 1;
			while ( j < n && (isAsciiAlnum(s.charAt(j)) || s.charAt(j) == '-' || s.charAt(j) == '_') ) {
				j++;
			}
			if ( j >= n || s.charAt(j) != ' '
					|| !BARE_KEYWORDS.contains(s.substring(i, j).toLowerCase(Locale.ROOT)) ) {
				i = j;
				continue;
			}
			final int vs = skipSpaces(s, j);
			int ve = vs;
			while ( ve < n && isTokenChar(s.charAt(ve)) ) {
				ve++;
			}
			// a word cut at the end of the text may be a partial credential
			final int min = (ve == n ? MIN_PARTIAL_SECRET : BARE_CREDENTIAL_LENGTH);
			if ( ve - vs >= min && hasLetterAndDigit(s, vs, ve) ) {
				buf = append(buf, s, copied, vs).append(MASK);
				copied = ve;
				count++;
			}
			i = Math.max(ve, j);
		}
		return finish(buf, s, copied);
	}

	private static boolean hasLetterAndDigit(String s, int from, int to) {
		boolean letter = false;
		boolean digit = false;
		for ( int i = from; i < to && !(letter && digit); i++ ) {
			final char c = s.charAt(i);
			if ( isDigit(c) ) {
				digit = true;
			} else if ( isAsciiAlnum(c) ) {
				letter = true;
			}
		}
		return letter && digit;
	}

	private String awsKeys(String s) {
		final int n = s.length();
		StringBuilder buf = null;
		int copied = 0;
		int i = 0;
		while ( i < n ) {
			if ( !isAsciiAlnum(s.charAt(i)) ) {
				i++;
				continue;
			}
			int j = i + 1;
			while ( j < n && isAsciiAlnum(s.charAt(j)) ) {
				j++;
			}
			// a run cut at the end of the text may be a partial key
			final int len = j - i;
			if ( (len == AWS_KEY_LENGTH || (j == n && len >= MIN_PARTIAL_SECRET && len < AWS_KEY_LENGTH))
					&& isAwsKey(s, i, j) ) {
				buf = append(buf, s, copied, i).append(MASK);
				copied = j;
				count++;
			}
			i = j;
		}
		return finish(buf, s, copied);
	}

	private static boolean isAwsKey(String s, int from, int to) {
		boolean prefix = false;
		for ( String p : AWS_KEY_PREFIXES ) {
			if ( s.startsWith(p, from) ) {
				prefix = true;
				break;
			}
		}
		if ( !prefix ) {
			return false;
		}
		for ( int k = from; k < to; k++ ) {
			final char c = s.charAt(k);
			if ( !(isDigit(c) || (c >= 'A' && c <= 'Z')) ) {
				return false;
			}
		}
		return true;
	}

	/*
	 * =========================================================================
	 * === Long tokens
	 * =========================================================================
	 * ===
	 */

	private String longTokens(String s) {
		final int n = s.length();
		StringBuilder buf = null;
		int copied = 0;
		int i = 0;
		while ( i < n ) {
			if ( !isTokenChar(s.charAt(i)) ) {
				i++;
				continue;
			}
			int j = i + 1;
			while ( j < n && isTokenChar(s.charAt(j)) ) {
				j++;
			}
			if ( j - i >= LONG_TOKEN_LENGTH ) {
				// split into segments: at '/' for absolute paths, and after a non-padding '='
				final boolean path = s.charAt(i) == '/';
				int segStart = i;
				for ( int k = i; k < j; k++ ) {
					final char c = s.charAt(k);
					if ( path && c == '/' ) {
						if ( isLongToken(s, segStart, k, j) ) {
							buf = append(buf, s, copied, segStart).append(TOKEN_MASK);
							copied = k;
							count++;
						}
						segStart = k + 1;
					} else if ( c == '=' && k + 1 < j && s.charAt(k + 1) != '=' ) {
						if ( isLongToken(s, segStart, k + 1, j) ) {
							buf = append(buf, s, copied, segStart).append(TOKEN_MASK);
							copied = k + 1;
							count++;
						}
						segStart = k + 1;
					}
				}
				if ( isLongToken(s, segStart, j, j) ) {
					buf = append(buf, s, copied, segStart).append(TOKEN_MASK);
					copied = j;
					count++;
				}
			}
			i = j;
		}
		return finish(buf, s, copied);
	}

	private static boolean isLongToken(String s, int from, int to, int runEnd) {
		if ( to - from < LONG_TOKEN_LENGTH ) {
			return false;
		}
		if ( to == runEnd && s.charAt(to - 1) == '=' ) {
			// base64 padding
			return true;
		}
		for ( int i = from; i < to; i++ ) {
			final char c = s.charAt(i);
			if ( isDigit(c) || c == '+' || c == '/' ) {
				return true;
			}
		}
		return false;
	}

	/*
	 * =========================================================================
	 * === Character classes and helpers
	 * =========================================================================
	 * ===
	 */

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static boolean isAsciiAlnum(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || isDigit(c);
	}

	private static boolean isKeyChar(char c) {
		return isAsciiAlnum(c) || c == '_' || c == '.' || c == '-';
	}

	private static boolean isQueryKeyChar(char c) {
		return isKeyChar(c) || c == '[' || c == ']';
	}

	private static boolean isTokenChar(char c) {
		return isAsciiAlnum(c) || c == '+' || c == '/' || c == '=' || c == '_' || c == '-';
	}

	private static boolean isBase64Char(char c) {
		return isAsciiAlnum(c) || c == '+' || c == '/' || c == '=';
	}

	private static boolean isClassNameChar(char c) {
		return isAsciiAlnum(c) || c == '_' || c == '$' || c == '.';
	}

	private static boolean isWhitespace(char c) {
		return c <= ' ' || Character.isSpaceChar(c);
	}

	private static boolean isWordStart(String s, int i) {
		return i == 0 || !isAsciiAlnum(s.charAt(i - 1));
	}

	private static boolean isBlank(String s, int from, int to) {
		for ( int i = from; i < to; i++ ) {
			if ( !isWhitespace(s.charAt(i)) ) {
				return false;
			}
		}
		return true;
	}

	private static boolean isMask(String s, int from, int to) {
		return to - from == MASK.length() && s.startsWith(MASK, from);
	}

	private static int skipSpaces(String s, int i) {
		int j = i;
		while ( j < s.length() && s.charAt(j) == ' ' ) {
			j++;
		}
		return j;
	}

	private static StringBuilder append(@Nullable StringBuilder buf, String s, int from, int to) {
		final StringBuilder b = (buf != null ? buf : new StringBuilder(s.length()));
		return b.append(s, from, to);
	}

	private static String finish(@Nullable StringBuilder buf, String s, int copied) {
		if ( buf == null ) {
			return s;
		}
		return buf.append(s, copied, s.length()).toString();
	}

}
