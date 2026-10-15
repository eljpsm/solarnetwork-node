/* ==================================================================
 * LogScannerTests.java - 16/10/2026 9:00:00 am
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
import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import net.solarnetwork.node.system.diagnostics.Deadline;
import net.solarnetwork.node.system.diagnostics.DeadlineExceededException;
import net.solarnetwork.node.system.diagnostics.LogEntry;
import net.solarnetwork.node.system.diagnostics.LogLayout;
import net.solarnetwork.node.system.diagnostics.LogScanner;
import net.solarnetwork.node.system.diagnostics.PatternNormalizer;
import net.solarnetwork.node.system.diagnostics.Redactor;

/**
 * Test cases for the {@link LogScanner} class.
 *
 * @author elijah
 * @version 1.0
 */
public class LogScannerTests {

	private static final long MAX_BYTES = 8L * 1024 * 1024;

	private static final String MODBUS_LOGGER = ".node.datum.modbus.ModbusDatumDataSource";

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static String header(String ts, String level, String logger, String msg) {
		String l = (logger.length() > 40 ? logger.substring(logger.length() - 40) : logger);
		return ts + " " + String.format("%-5s", level) + " " + String.format("%40s", l) + "; " + msg;
	}

	private static ReadableByteChannel channel(String s) {
		return channel(s.getBytes(UTF_8));
	}

	private static ReadableByteChannel channel(byte[] data) {
		return Channels.newChannel(new ByteArrayInputStream(data));
	}

	private static LogScanner scanner(LogLayout layout, ZoneId zone) {
		return new LogScanner(layout, zone, Deadline.ofMillis(60_000), MAX_BYTES);
	}

	private List<LogEntry> scanResource(LogScanner s, String name) throws IOException {
		final List<LogEntry> result = new ArrayList<>();
		try (InputStream in = getClass().getResourceAsStream(name)) {
			then(in).as("Resource %s exists", name).isNotNull();
			s.scan(Channels.newChannel(in), Long.MAX_VALUE, result::add);
		}
		return result;
	}

	@Test
	public void parseHeader_default() {
		// GIVEN
		final String line = header("2026-10-15 06:00:00,123", "ERROR",
				"net.solarnetwork.node.datum.modbus.ModbusDatumDataSource", "Hello; world");

		// WHEN
		LogEntry e = LogLayout.DEFAULT.parseHeader(line, ZoneOffset.UTC);

		// THEN
		// @formatter:off
		then(e)
			.isNotNull()
			.returns(Instant.parse("2026-10-15T06:00:00.123Z").toEpochMilli(), LogEntry::getTimestampMs)
			.returns("ERROR", LogEntry::getLevel)
			.returns(4, LogEntry::getLevelRank)
			.as("Logger truncated from the left to 40 characters by the layout")
			.returns(MODBUS_LOGGER, LogEntry::getLogger)
			.returns("Hello; world", LogEntry::getMessage)
			.returns(null, LogEntry::getRootException)
			;
		then(LogLayout.detect(line)).isSameAs(LogLayout.DEFAULT);
		then(LogLayout.NO_MILLIS.parseHeader(line, ZoneOffset.UTC)).isNull();
		// @formatter:on
	}

	@Test
	public void parseHeader_shortLoggerAndLevel() {
		// GIVEN
		final String line = header("2026-10-15 06:00:00,000", "INFO", "Foo", "");

		// WHEN
		LogEntry e = LogLayout.DEFAULT.parseHeader(line, ZoneOffset.UTC);

		// THEN
		// @formatter:off
		then(e)
			.isNotNull()
			.returns("INFO", LogEntry::getLevel)
			.returns("Foo", LogEntry::getLogger)
			.returns("", LogEntry::getMessage)
			;
		// @formatter:on
	}

	@Test
	public void parseHeader_noMillis() {
		// GIVEN
		final String line = header("2026-10-15 06:00:00", "WARN", "net.solarnetwork.node.Foo", "Hi");

		// WHEN
		LogEntry e = LogLayout.NO_MILLIS.parseHeader(line, ZoneOffset.UTC);

		// THEN
		// @formatter:off
		then(e)
			.isNotNull()
			.returns(Instant.parse("2026-10-15T06:00:00Z").toEpochMilli(), LogEntry::getTimestampMs)
			.returns("WARN", LogEntry::getLevel)
			.returns("net.solarnetwork.node.Foo", LogEntry::getLogger)
			.returns("Hi", LogEntry::getMessage)
			;
		then(LogLayout.detect(line)).isSameAs(LogLayout.NO_MILLIS);
		then(LogLayout.DEFAULT.parseHeader(line, ZoneOffset.UTC)).isNull();
		// @formatter:on
	}

	@Test
	public void parseHeader_malformed() {
		final String logger = "net.solarnetwork.node.Foo";
		// @formatter:off
		final List<String> lines = Arrays.asList(
				"",
				"2026-10-15 06:00:00,123",
				// logger column 39 wide
				"2026-10-15 06:00:00,123 ERROR " + String.format("%39s", logger) + "; msg",
				// logger column 41 wide
				"2026-10-15 06:00:00,123 ERROR " + String.format("%41s", logger) + "; msg",
				// missing separator
				"2026-10-15 06:00:00,123 ERROR " + String.format("%40s", logger) + ": msg",
				// unknown levels
				header("2026-10-15 06:00:00,123", "NOTE", logger, "msg"),
				header("2026-10-15 06:00:00,123", "WARNI", logger, "msg"),
				// level right-justified
				"2026-10-15 06:00:00,123  WARN " + String.format("%40s", logger) + "; msg",
				// bad timestamps
				header("2026-13-15 06:00:00,123", "ERROR", logger, "msg"),
				header("2026-02-30 06:00:00,123", "ERROR", logger, "msg"),
				header("2026-10-15 24:00:00,123", "ERROR", logger, "msg"),
				header("2026-10-15T06:00:00,123", "ERROR", logger, "msg"),
				header("2026-10-15 06:00:00.123", "ERROR", logger, "msg"),
				header("2026-1a-15 06:00:00,123", "ERROR", logger, "msg"),
				header("+026-10-15 06:00:00,123", "ERROR", logger, "msg"),
				// logger with a space in it
				header("2026-10-15 06:00:00,123", "ERROR", "net.solarnetwork Foo", "msg"),
				// blank logger
				header("2026-10-15 06:00:00,123", "ERROR", "", "msg"),
				"\tat net.solarnetwork.Foo.bar(Foo.java:123)",
				"06:00:00.000 [                main] ERROR .datum.modbus.ModbusDatumDataSource - msg"
				);
		// @formatter:on
		for ( String line : lines ) {
			then(LogLayout.DEFAULT.parseHeader(line, ZoneOffset.UTC)).as("Not a header: [%s]", line)
					.isNull();
			then(LogLayout.NO_MILLIS.parseHeader(line, ZoneOffset.UTC)).as("Not a header: [%s]", line)
					.isNull();
			then(LogLayout.UNSUPPORTED.parseHeader(line, ZoneOffset.UTC)).isNull();
			then(LogLayout.detect(line)).isSameAs(LogLayout.UNSUPPORTED);
		}
	}

	@Test
	public void scan_defaultFixture() throws IOException {
		// GIVEN
		LogScanner s = scanner(LogLayout.DEFAULT, ZoneOffset.UTC);

		// WHEN
		List<LogEntry> entries = scanResource(s, "solarnode-default.log");

		// THEN
		// @formatter:off
		then(s)
			.as("All lines read")
			.returns(323L, LogScanner::getLines)
			.as("All bytes read")
			.returns(33049L, LogScanner::getBytes)
			.as("All headers parsed, forged mis-aligned header is a continuation")
			.returns(156L, LogScanner::getEntries)
			.as("Audit entries dropped")
			.returns(2L, LogScanner::getAudit)
			.returns(0L, LogScanner::getUnparsed)
			.returns(0L, LogScanner::getLongLines)
			;
		then(entries)
			.as("Audit entries not emitted")
			.hasSize(154)
			.noneMatch(e -> e.getLogger().contains("AUDIT"))
			.noneMatch(e -> e.getMessage().contains("AUDITSECRET"))
			.noneMatch(e -> e.getMessage().contains("FORGEDLINE"))
			;
		then(entries.get(0))
			.as("Root exception is the last Caused by")
			.returns("java.net.NoRouteToHostException", LogEntry::getRootException)
			.as("Continuation text not included in message")
			.returns("Communication problem reading source /SITE-C/INV/3 from Modbus device "
					+ "192.168.20.33:502: No route to host", LogEntry::getMessage)
			;
		then(entries.get(1))
			.returns("INFO", LogEntry::getLevel)
			.returns(null, LogEntry::getRootException)
			;
		then(entries.stream().filter(e -> "FATAL".equals(e.getLevel())).findFirst().orElseThrow())
			.as("Exception from first exception line when no Caused by")
			.returns("FATAL", LogEntry::getLevel)
			.returns("java.lang.OutOfMemoryError", LogEntry::getRootException)
			;
		// @formatter:on
	}

	@Test
	public void scan_noMillisFixture() throws IOException {
		// GIVEN
		LogScanner s = scanner(LogLayout.NO_MILLIS, ZoneOffset.UTC);

		// WHEN
		List<LogEntry> entries = scanResource(s, "solarnode-nomillis.log");

		// THEN
		// @formatter:off
		then(entries)
			.hasSize(6)
			.extracting(LogEntry::getLevel)
			.containsExactly("ERROR", "INFO", "ERROR", "INFO", "ERROR", "INFO")
			;
		then(entries.get(4))
			.returns(Instant.parse("2026-10-15T06:20:00Z").toEpochMilli(), LogEntry::getTimestampMs)
			.returns("java.net.NoRouteToHostException", LogEntry::getRootException)
			;
		// @formatter:on
	}

	@Test
	public void scan_unparsedLinesBeforeFirstHeader() throws IOException {
		// GIVEN
		final String data = "tail of a previous entry\n\n\tat foo.Bar(Bar.java:1)\n"
				+ header("2026-10-15 06:00:00,000", "WARN", "a.B", "one") + "\r\n"
				+ "java.io.IOException: boom\r\n" + "\tat foo.Bar(Bar.java:1)\r\n"
				+ header("2026-10-15 06:00:01,000", "ERROR", "a.B", "two words");
		LogScanner s = scanner(LogLayout.DEFAULT, ZoneOffset.UTC);
		List<LogEntry> entries = new ArrayList<>();

		// WHEN
		boolean complete = s.scan(channel(data), Long.MAX_VALUE, entries::add);

		// THEN
		// @formatter:off
		then(complete).isTrue();
		then(s)
			.returns(7L, LogScanner::getLines)
			.returns(2L, LogScanner::getEntries)
			.as("Non-blank lines before first header are unparsed")
			.returns(2L, LogScanner::getUnparsed)
			;
		then(entries)
			.extracting(LogEntry::getMessage, LogEntry::getRootException)
			.as("CR stripped, final line without newline parsed, possibly cut final word removed")
			.containsExactly(
					tuple("one", "java.io.IOException"),
					tuple("two ", null))
			;
		// @formatter:on
	}

	@Test
	public void scan_causedByVariants() throws IOException {
		// GIVEN
		final String data = header("2026-10-15 06:00:00,000", "ERROR", "a.B", "one") + "\n"
				+ "org.example.SomeException: outer\n"
				+ "Caused by: org.example.Middle$InnerException: middle\n"
				+ "\tSuppressed: java.lang.IllegalStateException: x\n"
				+ "\t\tCaused by: java.lang.ArithmeticException\n" + "Caused by: not a class name.\n"
				+ "Caused by: a.b.\n" + header("2026-10-15 06:00:01,000", "ERROR", "a.B", "two") + "\n"
				+ "org.example.NotAnExceptionClass: hmm\n"
				+ "some text java.lang.RuntimeException: nope\n";
		LogScanner s = scanner(LogLayout.DEFAULT, ZoneOffset.UTC);
		List<LogEntry> entries = new ArrayList<>();

		// WHEN
		s.scan(channel(data), Long.MAX_VALUE, entries::add);

		// THEN
		// @formatter:off
		then(entries)
			.extracting(LogEntry::getRootException)
			.as("Last valid Caused by FQCN used; FQCN must end in Exception or Error otherwise")
			.containsExactly("java.lang.ArithmeticException", null)
			;
		// @formatter:on
	}

	@Test
	public void scan_auditDroppedWithContinuations() throws IOException {
		// GIVEN
		final String data = header("2026-10-15 06:00:00,000", "WARN",
				"net.solarnetwork.node.settings.AUDIT", "Updating settings: {password=s3cret}") + "\n"
				+ "java.lang.Exception: s3cret\n"
				+ header("2026-10-15 06:00:01,000", "ERROR", "a.B", "two") + "\n";
		LogScanner s = scanner(LogLayout.DEFAULT, ZoneOffset.UTC);
		List<LogEntry> entries = new ArrayList<>();

		// WHEN
		s.scan(channel(data), Long.MAX_VALUE, entries::add);

		// THEN
		// @formatter:off
		then(entries)
			.extracting(LogEntry::getMessage)
			.containsExactly("two")
			;
		then(s.getAudit()).isEqualTo(1L);
		// @formatter:on
	}

	@Test
	public void scan_dstOverlapUsesEarlierOffset() throws IOException {
		// GIVEN
		final ZoneId zone = ZoneId.of("America/Denver");
		final String data = header("2026-11-01 01:30:00,000", "ERROR", "a.B", "overlap") + "\n"
				+ header("2026-03-08 02:30:00,000", "ERROR", "a.B", "gap") + "\n";
		LogScanner s = scanner(LogLayout.DEFAULT, zone);
		List<LogEntry> entries = new ArrayList<>();

		// WHEN
		s.scan(channel(data), Long.MAX_VALUE, entries::add);

		// THEN
		// @formatter:off
		then(entries)
			.extracting(LogEntry::getTimestampMs)
			.as("Overlap resolved to earlier offset (MDT, -06:00); gap shifted forward to 03:30 MDT")
			.containsExactly(
					Instant.parse("2026-11-01T01:30:00-06:00").toEpochMilli(),
					Instant.parse("2026-03-08T03:30:00-06:00").toEpochMilli())
			;
		// @formatter:on
	}

	@Test
	public void scan_malformedUtf8() throws IOException {
		// GIVEN
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(header("2026-10-15 06:00:00,000", "ERROR", "a.B", "bad ").getBytes(UTF_8));
		out.write(new byte[] { (byte) 0xC3, (byte) 0x28, (byte) 0xFF, (byte) 0xE2, (byte) 0x82 });
		out.write(" end\n".getBytes(UTF_8));
		// invalid bytes inside the header columns
		out.write("2026-10-15 06:00:01,000 ERR".getBytes(UTF_8));
		out.write(new byte[] { (byte) 0xFF, (byte) 0xFE });
		out.write("\n".getBytes(UTF_8));
		LogScanner s = scanner(LogLayout.DEFAULT, ZoneOffset.UTC);
		List<LogEntry> entries = new ArrayList<>();

		// WHEN
		s.scan(channel(out.toByteArray()), Long.MAX_VALUE, entries::add);

		// THEN
		// @formatter:off
		then(entries).hasSize(1);
		then(entries.get(0).getMessage())
			.startsWith("bad ")
			.endsWith(" end")
			.contains("�")
			;
		then(s.getLines()).isEqualTo(2L);
		// @formatter:on
	}

	@Test
	public void scan_byteCap() throws IOException {
		// GIVEN
		LogScanner s = new LogScanner(LogLayout.DEFAULT, ZoneOffset.UTC, Deadline.ofMillis(60_000),
				4096);
		List<LogEntry> entries = new ArrayList<>();

		// WHEN
		boolean complete;
		try (InputStream in = getClass().getResourceAsStream("solarnode-default.log")) {
			complete = s.scan(Channels.newChannel(in), Long.MAX_VALUE, entries::add);
		}

		// THEN
		then(complete).as("Byte cap reached").isFalse();
		then(s.getBytes()).isEqualTo(4096L);
		then(s.getRemainingBytes()).isZero();
		then(entries).isNotEmpty();
	}

	@Test
	public void scan_fileLimit() throws IOException {
		// GIVEN
		final String one = header("2026-10-15 06:00:00,000", "ERROR", "a.B", "one") + "\n";
		final String data = one + header("2026-10-15 06:00:01,000", "ERROR", "a.B", "two") + "\n";
		LogScanner s = scanner(LogLayout.DEFAULT, ZoneOffset.UTC);
		List<LogEntry> entries = new ArrayList<>();

		// WHEN
		boolean complete = s.scan(channel(data), one.length(), entries::add);

		// THEN
		then(complete).as("File limit is not the byte cap").isTrue();
		then(entries).extracting(LogEntry::getMessage).containsExactly("one");
	}

	@Test
	public void scan_deadline() {
		// GIVEN
		LogScanner s = new LogScanner(LogLayout.DEFAULT, ZoneOffset.UTC, Deadline.ofMillis(0),
				MAX_BYTES);

		// THEN
		thenThrownBy(() -> s.scan(channel("x\n"), Long.MAX_VALUE, e -> {
		})).isInstanceOf(DeadlineExceededException.class);
	}

	@Test
	public void scan_unsupportedLayout() {
		thenThrownBy(() -> new LogScanner(LogLayout.UNSUPPORTED, ZoneOffset.UTC, Deadline.ofMillis(1000),
				MAX_BYTES)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	public void scan_hugeLineWithoutNewline() throws IOException {
		// GIVEN
		final Path f = tmp.newFile("huge.log").toPath();
		final byte[] chunk = new byte[64 * 1024];
		Arrays.fill(chunk, (byte) 'a');
		try (OutputStream out = Files.newOutputStream(f)) {
			out.write(header("2026-10-15 06:00:00,000", "ERROR", "a.B", "start ").getBytes(UTF_8));
			for ( int i = 0; i < 160; i++ ) {
				out.write(chunk);
			}
		}
		LogScanner s = new LogScanner(LogLayout.DEFAULT, ZoneOffset.UTC, Deadline.ofMillis(60_000),
				64L * 1024 * 1024);
		List<LogEntry> entries = new ArrayList<>();

		// WHEN
		final long start = System.nanoTime();
		try (FileChannel ch = FileChannel.open(f)) {
			s.scan(ch, Files.size(f), entries::add);
		}
		final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

		// THEN
		// @formatter:off
		then(s)
			.returns(Files.size(f), LogScanner::getBytes)
			.returns(1L, LogScanner::getLines)
			.returns(1L, LogScanner::getLongLines)
			;
		then(entries).hasSize(1);
		then(entries.get(0).getMessage().getBytes(UTF_8).length)
			.as("Line capped at 8 KB")
			.isLessThanOrEqualTo(LogScanner.MAX_LINE_BYTES)
			;
		then(ms).as("Scan is quick").isLessThan(5000L);
		// @formatter:on
	}

	private static String leak(String text, String secret) {
		for ( int i = 0; i + 6 <= secret.length(); i++ ) {
			String sub = secret.substring(i, i + 6);
			if ( text.contains(sub) ) {
				return sub;
			}
		}
		return null;
	}

	@Test
	public void scan_longLineCutInsideToken() throws IOException {
		// GIVEN
		final String token = "Zq9XwLmPq7RtYvB3nKcJdHfGs2AeUiOo8Tz";
		final String head = header("2026-10-15 06:00:00,000", "ERROR", "a.B", "");
		final List<String> failures = new ArrayList<>();

		for ( int k = 0; k <= token.length(); k++ ) {
			// place the 8 KB line cap k characters into the token
			final String start = "Error posting datum GeneralDatum{";
			final String end = "} signature ";
			final int fill = LogScanner.MAX_LINE_BYTES - head.length() - start.length() - end.length()
					- k;
			final String msg = start + "x".repeat(fill) + end + token + " rejected";
			LogScanner s = scanner(LogLayout.DEFAULT, ZoneOffset.UTC);
			List<LogEntry> entries = new ArrayList<>();

			// WHEN
			s.scan(channel(head + msg + "\n"), Long.MAX_VALUE, entries::add);

			// THEN
			then(entries).hasSize(1);
			final String out = new Redactor().redact(entries.get(0).getMessage());
			final String pattern = PatternNormalizer.normalize(out).getPattern();
			if ( leak(out, token) != null || leak(pattern, token.replaceAll("[0-9]", "")) != null ) {
				failures.add("cut " + k + ": " + pattern);
			}
		}
		then(failures).as("No partial token survives the line cap").isEmpty();
	}

	@Test
	public void scan_finalLineWithoutNewlineTrimmed() throws IOException {
		// GIVEN
		final String line = header("2026-10-15 06:00:00,000", "ERROR", "a.B", "signature Zq9XwLmPq7");
		LogScanner s = scanner(LogLayout.DEFAULT, ZoneOffset.UTC);
		List<LogEntry> entries = new ArrayList<>();

		// WHEN
		s.scan(channel(line), Long.MAX_VALUE, entries::add);

		// THEN
		then(entries).extracting(LogEntry::getMessage).as("Possibly cut final token removed")
				.containsExactly("signature ");
	}

	@Test
	public void scan_tail() throws IOException {
		// GIVEN
		final String data = "tial header line\n" + "\tat a.B.c(B.java:1)\n"
				+ header("2026-10-15 06:00:00,000", "ERROR", "a.B", "one") + "\n"
				+ header("2026-10-15 06:00:01,000", "ERROR", "a.B", "two") + "\n";
		LogScanner s = scanner(LogLayout.DEFAULT, ZoneOffset.UTC);
		List<LogEntry> entries = new ArrayList<>();

		// WHEN
		boolean complete = s.scan(channel(data), data.length(), true, entries::add);

		// THEN
		// @formatter:off
		then(complete).isTrue();
		then(entries).extracting(LogEntry::getMessage).containsExactly("one", "two");
		then(s)
			.as("Partial first line and orphan continuation not counted as unparsed")
			.returns(0L, LogScanner::getUnparsed)
			.returns((long) data.length(), LogScanner::getBytes)
			;
		// @formatter:on
	}

	@Test
	public void scan_tailNoNewline() throws IOException {
		// GIVEN
		final String data = "partial line without any line ending";
		LogScanner s = scanner(LogLayout.DEFAULT, ZoneOffset.UTC);
		List<LogEntry> entries = new ArrayList<>();

		// WHEN
		s.scan(channel(data), data.length(), true, entries::add);

		// THEN
		then(entries).isEmpty();
		then(s.getLines()).as("Partial first line skipped").isZero();
	}

	@Test
	public void scan_adversarialLongLines() throws IOException {
		// GIVEN
		final int n = LogScanner.MAX_LINE_BYTES - 64;
		final String[] bodies = new String[] { "a".repeat(n), "a.".repeat(n / 2),
				"Caused by: " + "a.".repeat(n / 2), "Caused by: " + "a".repeat(n), "1.".repeat(n / 2),
				"1".repeat(n), "\"".repeat(n), "\"a".repeat(n / 2), "a:1".repeat(n / 3),
				"/dev/".repeat(n / 5), "0x".repeat(n / 2), "aaaaaaaa-".repeat(n / 9),
				"source " + "/".repeat(n), " ".repeat(n),
				"Communication problem reading source " + "a".repeat(n) };
		StringBuilder buf = new StringBuilder();
		for ( int i = 0; i < 20; i++ ) {
			for ( String b : bodies ) {
				buf.append(header("2026-10-15 06:00:00,000", "ERROR", "a.B", b)).append('\n');
				buf.append(b).append('\n');
			}
		}
		LogScanner s = scanner(LogLayout.DEFAULT, ZoneOffset.UTC);
		List<String> patterns = new ArrayList<>();

		// WHEN
		final long start = System.nanoTime();
		s.scan(channel(buf.toString()), Long.MAX_VALUE,
				e -> patterns.add(PatternNormalizer.normalize(e.getMessage()).getPattern()));
		final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

		// THEN
		then(patterns).hasSize(20 * bodies.length);
		then(ms).as("Linear time processing").isLessThan(5000L);
	}

	@Test
	public void firstLine() throws IOException {
		// GIVEN
		final String data = "\n  \r\n" + header("2026-10-15 06:00:00,000", "ERROR", "a.B", "x")
				+ "\r\nnext\n";

		// WHEN
		String line = LogScanner.firstLine(channel(data));

		// THEN
		then(line).isEqualTo(header("2026-10-15 06:00:00,000", "ERROR", "a.B", "x"));
		then(LogScanner.firstLine(channel(""))).isNull();
		then(LogScanner.firstLine(channel("\n\n"))).isNull();
	}

}
