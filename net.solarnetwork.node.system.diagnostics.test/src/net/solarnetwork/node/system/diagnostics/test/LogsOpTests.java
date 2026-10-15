/* ==================================================================
 * LogsOpTests.java - 16/10/2026 9:00:00 am
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

import static java.util.Map.entry;
import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenIllegalArgumentException;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.assertj.core.groups.Tuple.tuple;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import net.solarnetwork.node.system.diagnostics.Deadline;
import net.solarnetwork.node.system.diagnostics.DiagnosticOp.RateClass;
import net.solarnetwork.node.system.diagnostics.DiagnosticResult;
import net.solarnetwork.node.system.diagnostics.DiagnosticsPaths;
import net.solarnetwork.node.system.diagnostics.LogFiles;
import net.solarnetwork.node.system.diagnostics.LogFiles.LogFile;
import net.solarnetwork.node.system.diagnostics.LogLayout;
import net.solarnetwork.node.system.diagnostics.LogsOp;
import net.solarnetwork.node.system.diagnostics.OpContext;
import net.solarnetwork.node.system.diagnostics.Redactor;

/**
 * Test cases for the {@link LogsOp} class.
 *
 * @author elijah
 * @version 1.0
 */
public class LogsOpTests {

	private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
	private static final long MAX_BYTES = 8L * 1024 * 1024;

	private static final String MODBUS_LOGGER = ".node.datum.modbus.ModbusDatumDataSource";
	private static final String INV_PATTERN = "Communication problem reading source <source> from "
			+ "Modbus device <target>: No route to host";

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private Path logDir;
	private LogsOp op;

	@Before
	public void setup() throws IOException {
		DiagnosticsPaths paths = DiagnosticsPaths.rootedAt(tmp.getRoot().toPath());
		logDir = paths.getLogDir();
		op = new LogsOp(paths);
	}

	private Path copy(String resource, String name, String mtime) throws IOException {
		Files.createDirectories(logDir);
		Path dest = logDir.resolve(name);
		try (InputStream in = getClass().getResourceAsStream(resource)) {
			then(in).as("Resource %s exists", resource).isNotNull();
			Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
		}
		Files.setLastModifiedTime(dest, FileTime.from(Instant.parse(mtime)));
		return dest;
	}

	private void defaultFiles() throws IOException {
		copy("solarnode-default.log", "solarnode.log", "2026-10-15T11:55:01.123Z");
		copy("solarnode-default-1.log", "solarnode-1.log", "2026-10-15T05:55:00Z");
	}

	private static Map<String, String> params(String... kv) {
		Map<String, String> m = new HashMap<>();
		for ( int i = 0; i < kv.length; i += 2 ) {
			m.put(kv[i], kv[i + 1]);
		}
		return m;
	}

	private static OpContext ctx(Map<String, String> params) {
		return ctx(params, Deadline.ofMillis(60_000), MAX_BYTES);
	}

	private static OpContext ctx(Map<String, String> params, Deadline deadline, long maxBytes) {
		return new OpContext(params, NOW, ZoneOffset.UTC, deadline, new Redactor(), maxBytes);
	}

	private static String header(String ts, String level, String logger, String msg) {
		return ts + " " + String.format("%-5s", level) + " " + String.format("%40s", logger) + "; "
				+ msg;
	}

	private static long ms(String ts) {
		return Instant.parse(ts).toEpochMilli();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> summaries(DiagnosticResult r) {
		return (List<Map<String, Object>>) r.getData().get("summaries");
	}

	private static Map<String, Object> summary(DiagnosticResult r, String pattern) {
		return summaries(r).stream().filter(m -> pattern.equals(m.get("pattern"))).findFirst()
				.orElseThrow();
	}

	@Test
	public void info() {
		then(op.name()).isEqualTo("logs");
		then(op.version()).isEqualTo(1);
		then(op.rateClass()).isSameAs(RateClass.SCAN);
		then(op.parameterNames()).containsExactlyInAnyOrder("sinceMs", "untilMs", "minLevel", "source",
				"limit");
	}

	@Test
	public void summary_defaults() throws Exception {
		// GIVEN
		defaultFiles();

		// WHEN
		DiagnosticResult r = op.run(ctx(Map.of()));

		// THEN
		// @formatter:off
		then(r.getNotAvailable()).isEmpty();
		then(r.getData())
			.containsEntry("layout", "default")
			.containsEntry("request", Map.of(
					"sinceMs", ms("2026-10-14T12:00:00Z"),
					"untilMs", NOW.toEpochMilli(),
					"minLevel", "WARN",
					"limit", 20))
			.containsEntry("window", Map.of(
					"requested", Map.of(
							"sinceMs", ms("2026-10-14T12:00:00Z"),
							"untilMs", NOW.toEpochMilli()),
					"available", Map.of(
							"earliestMs", ms("2026-10-14T06:00:00.005Z"),
							"latestMs", ms("2026-10-15T11:55:01.123Z"))))
			.containsEntry("scanned", Map.of(
					"files", 2,
					"bytes", 33049L + 6917L,
					"lines", 323L + 51L,
					"entries", 156L + 51L,
					"matched", 96L + 3L + 5L + 1L,
					"unparsed", 0L,
					"longLines", 0L,
					"rotatedDuringScan", false,
					"complete", true))
			.containsEntry("otherPatterns", Map.of("keys", 0L, "entries", 0L))
			.doesNotContainKey("otherSources")
			;
		then(summaries(r))
			.as("Sorted by level then count")
			.extracting(m -> m.get("level"), m -> m.get("count"))
			.containsExactly(
					tuple("FATAL", 1L),
					tuple("ERROR", 96L),
					tuple("ERROR", 3L),
					tuple("WARN", 5L))
			;
		then(summary(r, INV_PATTERN))
			.containsOnly(
				entry("level", "ERROR"),
				entry("logger", MODBUS_LOGGER),
				entry("pattern", INV_PATTERN),
				entry("count", 96L),
				entry("firstSeenMs", ms("2026-10-14T18:00:00Z")),
				entry("lastSeenMs", ms("2026-10-15T11:55:00.123Z")),
				entry("targets", List.of(Map.of("value", "192.168.20.33:502", "count", 96L))),
				entry("sources", List.of(Map.of("value", "/SITE-C/INV/3", "count", 96L))),
				entry("exception", Map.of("root", "java.net.NoRouteToHostException")))
			;
		then(summaries(r).get(0))
			.containsEntry("exception", Map.of("root", "java.lang.OutOfMemoryError"))
			;
		then(summaries(r).get(3))
			.containsEntry("targets", List.of(Map.of("value", "queue.solarnetwork.net:8883",
					"count", 5L)))
			.containsEntry("sources", List.of())
			;
		then(r.getData().toString())
			.as("Settings audit entries dropped")
			.doesNotContain("AUDIT")
			.as("Continuation text never output")
			.doesNotContain("FORGEDLINE")
			.doesNotContain("hunter2")
			.doesNotContain("Connect failed")
			.as("No INFO entries")
			.doesNotContain("Processed")
			;
		// @formatter:on
	}

	@Test
	public void minLevelError() throws Exception {
		// GIVEN
		defaultFiles();

		// WHEN
		DiagnosticResult r = op.run(ctx(params("minLevel", "ERROR")));

		// THEN
		// @formatter:off
		then(summaries(r))
			.extracting(m -> m.get("level"))
			.containsExactly("FATAL", "ERROR", "ERROR")
			;
		then(r.getData())
			.extractingByKey("scanned", MAP)
			.containsEntry("matched", 100L)
			;
		// @formatter:on
	}

	@Test
	public void window_skipsOlderFiles() throws Exception {
		// GIVEN
		defaultFiles();

		// WHEN
		DiagnosticResult r = op.run(ctx(params("sinceMs", String.valueOf(ms("2026-10-15T06:00:00Z")))));

		// THEN
		// @formatter:off
		then(r.getData())
			.extractingByKey("scanned", MAP)
			.as("Older file with mtime before since skipped")
			.containsEntry("files", 1)
			.containsEntry("bytes", 33049L)
			;
		then(r.getData())
			.extractingByKey("window", MAP)
			.extractingByKey("available", MAP)
			.as("Earliest available from oldest file even when skipped")
			.containsEntry("earliestMs", ms("2026-10-14T06:00:00.005Z"))
			;
		then(summary(r, INV_PATTERN))
			.containsEntry("count", 72L)
			.containsEntry("firstSeenMs", ms("2026-10-15T06:00:00.123Z"))
			;
		// @formatter:on
	}

	@Test
	public void window_filtersEntries() throws Exception {
		// GIVEN
		defaultFiles();

		// WHEN
		DiagnosticResult r = op.run(ctx(params("sinceMs", String.valueOf(ms("2026-10-15T00:00:00Z")),
				"untilMs", String.valueOf(ms("2026-10-15T06:00:00Z")))));

		// THEN
		// @formatter:off
		then(r.getData())
			.extractingByKey("scanned", MAP)
			.containsEntry("files", 2)
			.containsEntry("matched", 12L)
			;
		then(summaries(r)).hasSize(1);
		then(summary(r, INV_PATTERN))
			.containsEntry("count", 12L)
			.containsEntry("firstSeenMs", ms("2026-10-15T00:00:00Z"))
			.containsEntry("lastSeenMs", ms("2026-10-15T05:30:00Z"))
			.as("No exception in this window")
			.doesNotContainKey("exception")
			;
		// @formatter:on
	}

	@Test
	public void sourceFilter() throws Exception {
		// GIVEN
		defaultFiles();

		// WHEN
		DiagnosticResult r = op.run(ctx(params("source", "/SITE-C/INV/3")));

		// THEN
		// @formatter:off
		then(summaries(r))
			.hasSize(1)
			.first()
			.returns(INV_PATTERN, m -> m.get("pattern"))
			.returns(96L, m -> m.get("count"))
			;
		then(r.getData())
			.containsEntry("otherSources", List.of(Map.of("source", "/SITE-C/INV/1", "count", 3L)))
			.extractingByKey("scanned", MAP)
			.containsEntry("matched", 96L)
			;
		then(r.getData())
			.extractingByKey("request", MAP)
			.containsEntry("source", "/SITE-C/INV/3")
			;
		// @formatter:on
	}

	@Test
	public void sourceFilter_noMatch() throws Exception {
		// GIVEN
		defaultFiles();

		// WHEN
		DiagnosticResult r = op.run(ctx(params("source", "/SITE-C/INV")));

		// THEN
		then(summaries(r)).isEmpty();
		then(r.getData()).extractingByKey("otherSources", LIST).hasSize(2);
	}

	@Test
	public void limit() throws Exception {
		// GIVEN
		defaultFiles();

		// WHEN
		DiagnosticResult r = op.run(ctx(params("limit", "1")));

		// THEN
		// @formatter:off
		then(summaries(r))
			.extracting(m -> m.get("level"))
			.containsExactly("FATAL")
			;
		then(r.getData())
			.containsEntry("otherPatterns", Map.of("keys", 3L, "entries", 96L + 3L + 5L))
			;
		// @formatter:on
	}

	@Test
	public void noMillisLayout() throws Exception {
		// GIVEN
		copy("solarnode-nomillis.log", "solarnode.log", "2026-10-15T06:20:01Z");

		// WHEN
		DiagnosticResult r = op.run(ctx(Map.of()));

		// THEN
		// @formatter:off
		then(r.getData())
			.containsEntry("layout", "noMillis")
			;
		then(summaries(r))
			.hasSize(1)
			.first()
			.returns(3L, m -> m.get("count"))
			.returns(ms("2026-10-15T06:00:00Z"), m -> m.get("firstSeenMs"))
			.returns(ms("2026-10-15T06:20:00Z"), m -> m.get("lastSeenMs"))
			.returns(Map.of("root", "java.net.NoRouteToHostException"), m -> m.get("exception"))
			;
		// @formatter:on
	}

	@Test
	public void unsupportedLayout() throws Exception {
		// GIVEN
		copy("solarnode-custom.log", "solarnode.log", "2026-10-15T06:20:01Z");

		// WHEN
		DiagnosticResult r = op.run(ctx(Map.of()));

		// THEN
		// @formatter:off
		then(r.getNotAvailable())
			.containsExactly(Map.of("item", "logs", "reason", "unsupported"))
			;
		then(r.getData())
			.containsEntry("layout", "unsupported")
			.doesNotContainKeys("summaries", "scanned")
			;
		// @formatter:on
	}

	@Test
	public void missingDir() throws Exception {
		// WHEN
		DiagnosticResult r = op.run(ctx(Map.of()));

		// THEN
		// @formatter:off
		then(r.getNotAvailable())
			.containsExactly(Map.of("item", "logs", "reason", "absent"))
			;
		then(r.getData())
			.containsKeys("request", "window")
			.doesNotContainKeys("summaries", "scanned", "layout")
			;
		// @formatter:on
	}

	@Test
	public void emptyDir() throws Exception {
		// GIVEN
		Files.createDirectories(logDir);
		Files.createFile(logDir.resolve("other.log"));

		// WHEN
		DiagnosticResult r = op.run(ctx(Map.of()));

		// THEN
		then(r.getNotAvailable()).containsExactly(Map.of("item", "logs", "reason", "absent"));
	}

	@Test
	public void deadlineExpired() throws Exception {
		// GIVEN
		defaultFiles();

		// WHEN
		DiagnosticResult r = op.run(ctx(Map.of(), Deadline.ofMillis(0), MAX_BYTES));

		// THEN
		// @formatter:off
		then(r.getNotAvailable())
			.containsExactly(Map.of("item", "logs/scan", "reason", "timeout"))
			;
		then(r.getData())
			.containsKeys("request", "window", "layout", "summaries", "otherPatterns")
			.extractingByKey("scanned", MAP)
			.containsEntry("complete", false)
			.containsEntry("files", 0)
			;
		// @formatter:on
	}

	@Test
	public void interrupted() throws Exception {
		// GIVEN
		defaultFiles();

		// WHEN
		DiagnosticResult r;
		Thread.currentThread().interrupt();
		try {
			r = op.run(ctx(Map.of()));
		} finally {
			Thread.interrupted();
		}

		// THEN
		// @formatter:off
		then(r.getNotAvailable())
			.as("Interrupted while taking the file snapshot")
			.containsExactly(Map.of("item", "logs", "reason", "timeout"))
			;
		then(r.getData())
			.containsKeys("request", "window")
			.doesNotContainKeys("summaries")
			;
		// @formatter:on
	}

	@Test
	public void byteCap() throws Exception {
		// GIVEN
		defaultFiles();

		// WHEN
		DiagnosticResult r = op.run(ctx(Map.of(), Deadline.ofMillis(60_000), 33049L + 100));

		// THEN
		// @formatter:off
		then(r.getNotAvailable()).isEmpty();
		then(r.getData())
			.extractingByKey("scanned", MAP)
			.containsEntry("complete", false)
			.containsEntry("bytes", 33049L + 100)
			.containsEntry("files", 2)
			;
		then(summary(r, INV_PATTERN))
			.as("Newest file read in full")
			.containsEntry("count", 72L)
			;
		// @formatter:on
	}

	@Test
	public void byteCap_exactlyNewestFile() throws Exception {
		// GIVEN
		defaultFiles();

		// WHEN
		DiagnosticResult r = op.run(ctx(Map.of(), Deadline.ofMillis(60_000), 33049L));

		// THEN
		// @formatter:off
		then(r.getData())
			.extractingByKey("scanned", MAP)
			.containsEntry("complete", false)
			.containsEntry("files", 1)
			;
		// @formatter:on
	}

	@Test
	public void byteCap_newestFileTailScanned() throws Exception {
		// GIVEN
		Files.createDirectories(logDir);
		final StringBuilder buf = new StringBuilder();
		for ( int i = 0; i < 400; i++ ) {
			buf.append(header("2026-10-15 10:00:00,000", "WARN", "a.Old", "old warning number " + i))
					.append('\n');
		}
		for ( int i = 0; i < 5; i++ ) {
			buf.append(header("2026-10-15 11:59:00,000", "ERROR", "a.New", "RECENT FAILURE"))
					.append('\n');
		}
		final Path f = logDir.resolve("solarnode.log");
		Files.writeString(f, buf.toString());
		Files.setLastModifiedTime(f, FileTime.from(NOW.minusSeconds(60)));
		final long maxBytes = 2048;

		// WHEN
		DiagnosticResult r = op.run(ctx(Map.of(), Deadline.ofMillis(60_000), maxBytes));

		// THEN
		// @formatter:off
		then(r.getData())
			.extractingByKey("scanned", MAP)
			.containsEntry("complete", false)
			.containsEntry("bytes", maxBytes)
			.containsEntry("unparsed", 0L)
			;
		then(summary(r, "RECENT FAILURE"))
			.as("Most recent entries scanned from the end of the file")
			.containsEntry("count", 5L)
			;
		then(r.getData())
			.extractingByKey("window", MAP)
			.as("Earliest scanned entry reported")
			.containsEntry("scannedFromMs", ms("2026-10-15T10:00:00Z"))
			.extractingByKey("available", MAP)
			.containsEntry("earliestMs", ms("2026-10-15T10:00:00Z"))
			.containsEntry("latestMs", ms("2026-10-15T11:59:00Z"))
			;
		then(summaries(r))
			.as("Only the end of the old warnings scanned")
			.filteredOn(m -> "WARN".equals(m.get("level")))
			.allSatisfy(m -> then((Long) m.get("count")).isLessThan(400L))
			;
		// @formatter:on
	}

	@Test
	public void locateFailure_partialResult() throws Exception {
		// GIVEN
		copy("solarnode-default.log", "solarnode.log", "2026-10-15T11:55:01.123Z");
		final int[] calls = new int[1];
		final Deadline deadline = new Deadline(60_000L, () -> {
			if ( ++calls[0] == 2 ) {
				// rotate after the snapshot, then make the directory unreadable
				try {
					Files.move(logDir.resolve("solarnode.log"), logDir.resolve("solarnode-1.log"));
					Files.writeString(logDir.resolve("solarnode.log"), "");
					Files.setPosixFilePermissions(logDir, PosixFilePermissions.fromString("---------"));
				} catch ( IOException e ) {
					throw new RuntimeException(e);
				}
			}
			return System.nanoTime();
		});

		// WHEN
		DiagnosticResult r;
		try {
			r = op.run(ctx(Map.of(), deadline, MAX_BYTES));
		} finally {
			Files.setPosixFilePermissions(logDir, PosixFilePermissions.fromString("rwx------"));
		}

		// THEN
		// @formatter:off
		then(r.getNotAvailable())
			.as("File that cannot be located reported")
			.containsExactly(Map.of("item", "logs:solarnode.log", "reason", "denied"))
			;
		then(r.getData())
			.extractingByKey("scanned", MAP)
			.containsEntry("complete", false)
			;
		// @formatter:on
	}

	@Test
	public void duplicateFileKey_notDoubleCounted() throws Exception {
		// GIVEN
		Path f = copy("solarnode-default.log", "solarnode.log", "2026-10-15T11:55:01.123Z");
		Files.createLink(logDir.resolve("solarnode-1.log"), f);

		// WHEN
		DiagnosticResult r = op.run(ctx(Map.of()));

		// THEN
		// @formatter:off
		then(r.getData())
			.extractingByKey("scanned", MAP)
			.containsEntry("files", 1)
			.containsEntry("bytes", 33049L)
			;
		then(summary(r, INV_PATTERN)).containsEntry("count", 72L);
		// @formatter:on
	}

	@Test
	public void symlinksAndOtherFilesIgnored() throws Exception {
		// GIVEN
		defaultFiles();
		Path other = tmp.newFile("solarnode-other.log").toPath();
		Files.write(other, Files.readAllBytes(logDir.resolve("solarnode.log")));
		Files.createSymbolicLink(logDir.resolve("solarnode-2.log"), other);
		Files.createDirectories(logDir.resolve("solarnode-3.log"));
		Files.copy(logDir.resolve("solarnode.log"), logDir.resolve("solarnode-10.log"));
		Files.copy(logDir.resolve("solarnode.log"), logDir.resolve("solarnode.log.1"));

		// WHEN
		DiagnosticResult r = op.run(ctx(Map.of()));

		// THEN
		// @formatter:off
		then(r.getData())
			.extractingByKey("scanned", MAP)
			.containsEntry("files", 2)
			;
		then(summary(r, INV_PATTERN)).containsEntry("count", 96L);
		// @formatter:on
	}

	@Test
	public void rotation_locate() throws Exception {
		// GIVEN
		defaultFiles();
		LogFiles files = LogFiles.snapshot(logDir);
		then(files).isNotNull();
		List<LogFile> list = files.getFiles();
		then(list).extracting(LogFile::getName).containsExactly("solarnode.log", "solarnode-1.log");

		// WHEN
		// rotate: -1 -> -2, current -> -1, new current
		Files.move(logDir.resolve("solarnode-1.log"), logDir.resolve("solarnode-2.log"));
		Files.move(logDir.resolve("solarnode.log"), logDir.resolve("solarnode-1.log"));
		copy("solarnode-nomillis.log", "solarnode.log", "2026-10-15T11:59:00Z");

		// THEN
		then(files.locate(list.get(0))).isEqualTo(logDir.resolve("solarnode-1.log"));
		then(files.locate(list.get(1))).isEqualTo(logDir.resolve("solarnode-2.log"));
		Files.delete(logDir.resolve("solarnode-2.log"));
		then(files.locate(list.get(1))).isNull();
	}

	@Test
	public void capabilities() throws Exception {
		// GIVEN
		defaultFiles();

		// WHEN
		Map<String, Object> caps = op.capabilities(ctx(Map.of()));

		// THEN
		// @formatter:off
		then(caps)
			.containsEntry("supported", true)
			.containsEntry("layout", "default")
			.containsEntry("dir", logDir.toString())
			.containsEntry("files", List.of(
					Map.of("name", "solarnode.log", "sizeBytes", 33049L,
							"mtimeMs", ms("2026-10-15T11:55:01.123Z")),
					Map.of("name", "solarnode-1.log", "sizeBytes", 6917L,
							"mtimeMs", ms("2026-10-15T05:55:00Z"))))
			;
		// @formatter:on
	}

	@Test
	public void capabilities_missingDir() {
		// WHEN
		Map<String, Object> caps = op.capabilities(ctx(Map.of()));

		// THEN
		// @formatter:off
		then(caps)
			.containsEntry("supported", false)
			.containsEntry("files", List.of())
			;
		// @formatter:on
	}

	@Test
	public void capabilities_unsupported() throws Exception {
		// GIVEN
		copy("solarnode-custom.log", "solarnode.log", "2026-10-15T06:20:01Z");

		// WHEN
		Map<String, Object> caps = op.capabilities(ctx(Map.of()));

		// THEN
		then(caps).containsEntry("supported", false).containsEntry("layout", "unsupported");
	}

	@Test
	public void layoutDetectedFromNewestNonEmptyFile() throws Exception {
		// GIVEN
		copy("solarnode-default.log", "solarnode-1.log", "2026-10-15T11:00:00Z");
		Files.createDirectories(logDir);
		Path empty = Files.createFile(logDir.resolve("solarnode.log"));
		Files.setLastModifiedTime(empty, FileTime.from(Instant.parse("2026-10-15T11:59:00Z")));

		// WHEN
		LogFiles files = LogFiles.snapshot(logDir);

		// THEN
		then(files).isNotNull().returns(LogLayout.DEFAULT, LogFiles::getLayout);
		then(files.getFiles()).extracting(LogFile::getName).containsExactly("solarnode.log",
				"solarnode-1.log");
	}

	@Test
	public void validate() {
		// @formatter:off
		thenIllegalArgumentException()
			.isThrownBy(() -> op.validate(params("limit", "31")))
			.withMessage("Invalid parameter: limit")
			;
		thenIllegalArgumentException()
			.isThrownBy(() -> op.validate(params("minLevel", "INFO")))
			.withMessage("Invalid parameter: minLevel")
			;
		// @formatter:on
		op.validate(params("untilMs", String.valueOf(NOW.toEpochMilli() + 86_400_000L)));
	}

	@Test
	public void validate_now() {
		// @formatter:off
		thenIllegalArgumentException()
			.as("untilMs too far in the future")
			.isThrownBy(() -> op.validate(params("untilMs",
					String.valueOf(NOW.toEpochMilli() + 86_400_000L)), NOW))
			.withMessage("Invalid parameter: untilMs")
			;
		thenIllegalArgumentException()
			.as("sinceMs only, more than 14 days before now")
			.isThrownBy(() -> op.validate(params("sinceMs",
					String.valueOf(NOW.toEpochMilli() - 20L * 86_400_000L)), NOW))
			;
		thenIllegalArgumentException()
			.as("sinceMs only, after now")
			.isThrownBy(() -> op.validate(params("sinceMs",
					String.valueOf(NOW.toEpochMilli() + 1000L)), NOW))
			;
		// @formatter:on
		op.validate(params("sinceMs", String.valueOf(NOW.toEpochMilli() - 86_400_000L)), NOW);
	}

	@Test
	public void run_untilTooFarInFuture() {
		// @formatter:off
		thenIllegalArgumentException()
			.isThrownBy(() -> op.run(ctx(params("untilMs",
					String.valueOf(NOW.toEpochMilli() + 86_400_000L)))))
			.withMessage("Invalid parameter: untilMs")
			;
		// @formatter:on
	}

}
