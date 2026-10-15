/* ==================================================================
 * DiagnosticsServiceIntegrationTests.java - 16/10/2026 9:00:00 am
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
import static net.solarnetwork.node.reactor.InstructionHandler.PARAM_MESSAGE;
import static net.solarnetwork.node.reactor.InstructionHandler.PARAM_SERVICE;
import static net.solarnetwork.node.reactor.InstructionHandler.PARAM_SERVICE_RESULT;
import static net.solarnetwork.node.reactor.InstructionHandler.TOPIC_SYSTEM_CONFIGURATION;
import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.assertj.core.groups.Tuple.tuple;
import static org.easymock.EasyMock.expect;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import net.solarnetwork.domain.InstructionStatus.InstructionState;
import net.solarnetwork.node.reactor.Instruction;
import net.solarnetwork.node.reactor.InstructionStatus;
import net.solarnetwork.node.reactor.InstructionUtils;
import net.solarnetwork.node.service.PlatformService;
import net.solarnetwork.node.service.PlatformService.PlatformState;
import net.solarnetwork.node.system.diagnostics.DiagnosticResult.Reason;
import net.solarnetwork.node.system.diagnostics.DiagnosticsPaths;
import net.solarnetwork.node.system.diagnostics.DiagnosticsService;
import net.solarnetwork.node.system.diagnostics.LogsOp;
import net.solarnetwork.node.system.diagnostics.OsgiRuntimeInfo;
import net.solarnetwork.node.system.diagnostics.ResultEncoder;
import net.solarnetwork.node.system.diagnostics.StorageOp;
import net.solarnetwork.node.system.diagnostics.SystemOp;
import net.solarnetwork.service.OptionalService;

/**
 * Integration test cases for the {@link DiagnosticsService} class, using the
 * real operations, redactor, and result encoder over a fake node file system.
 *
 * @author elijah
 * @version 1.0
 */
public class DiagnosticsServiceIntegrationTests {

	private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
	private static final ZoneId ZONE = ZoneOffset.UTC;
	private static final DateTimeFormatter LOG_TS = DateTimeFormatter
			.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");
	private static final int MIN_SECRET_LENGTH = 6;
	private static final String FILE_CONTENT_SECRET = "F1le-C0ntent-Secret-Xy";

	private static final String MODBUS_LOGGER = ".node.datum.modbus.ModbusDatumDataSource";
	private static final String INV_PATTERN = "Communication problem reading source <source> from "
			+ "Modbus device <target>: No route to host";
	private static final String AUDIT_LOGGER = "net.solarnetwork.node.settings.AUDIT";

	// @formatter:off
	private static final String[] CORPUS_LOGGERS = new String[] {
			"net.solarnetwork.node.upload.mqtt.MqttUploadService",
			"net.solarnetwork.node.runtime.SimpleInstructionExecutionService",
			"net.solarnetwork.node.system.cmdline.CmdlineSystemService",
			"net.solarnetwork.node.weather.owm.JsonOwmClient",
			"net.solarnetwork.node.settings.ca.CASettingsService",
			"net.solarnetwork.node.setup.web.support.WebServiceControllerSupport",
	};
	// @formatter:on

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private List<Object> mocks;
	private BundleContext bundleContext;
	private OptionalService<PlatformService> platformServiceRef;
	private PlatformService platformService;
	private DiagnosticsPaths paths;
	private AtomicLong nanoOffset;
	private DiagnosticsService service;

	@SuppressWarnings("unchecked")
	@Before
	public void setup() {
		mocks = new ArrayList<>(4);
		bundleContext = mock(BundleContext.class);
		platformServiceRef = mock(OptionalService.class);
		platformService = mock(PlatformService.class);
		paths = DiagnosticsPaths.rootedAt(tmp.getRoot().toPath());
		nanoOffset = new AtomicLong();
		service = new DiagnosticsService(
				List.of(new SystemOp(bundleContext, platformServiceRef, paths),
						new StorageOp(bundleContext, paths), new LogsOp(paths)),
				Clock.fixed(NOW, ZONE), () -> System.nanoTime() + nanoOffset.get(), ZONE);
	}

	@After
	public void teardown() {
		service.shutdown();
		EasyMock.verify(mocks.toArray());
	}

	private <T> T mock(Class<T> clazz) {
		T m = EasyMock.createStrictMock(clazz);
		mocks.add(m);
		return m;
	}

	private void replayAll() {
		EasyMock.replay(mocks.toArray());
	}

	/**
	 * Advance the service monotonic clock so that rate limit tokens refill.
	 */
	private void advanceOneHour() {
		nanoOffset.addAndGet(TimeUnit.HOURS.toNanos(1));
	}

	private InstructionStatus exec(String op, String... kv) {
		Map<String, String> params = new LinkedHashMap<>(8);
		params.put(PARAM_SERVICE, DiagnosticsService.DIAGNOSTICS_SERVICE_UID);
		params.put(DiagnosticsService.PARAM_OP, op);
		params.put(DiagnosticsService.PARAM_VERSION, "1");
		for ( int i = 0; i + 1 < kv.length; i += 2 ) {
			params.put(kv[i], kv[i + 1]);
		}
		Instruction instr = InstructionUtils.createLocalInstruction(TOPIC_SYSTEM_CONFIGURATION,
				NOW.minusSeconds(5), params);
		return service.processInstruction(instr);
	}

	/**
	 * Assert a status is completed within the result budget and decode the
	 * result JSON text.
	 */
	private static String completedJson(InstructionStatus status) throws IOException {
		// @formatter:off
		then(status)
			.as("Status returned %s", status == null ? null : status.getResultParameters())
			.isNotNull()
			.returns(InstructionState.Completed, InstructionStatus::getInstructionState)
			;
		then(status.getResultParameters())
			.as("Only result parameter")
			.containsOnlyKeys(PARAM_SERVICE_RESULT)
			;
		// @formatter:on
		Object result = status.getResultParameters().get(PARAM_SERVICE_RESULT);
		then(result).as("Result is string").isInstanceOf(String.class);
		String encoded = (String) result;
		then(encoded.length()).as("Encoded result within budget")
				.isLessThanOrEqualTo(DiagnosticsService.RESULT_BUDGET);
		byte[] gz = Base64.getDecoder().decode(encoded);
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
			return new String(in.readAllBytes(), UTF_8);
		}
	}

	private static Map<String, Object> completed(InstructionStatus status) throws IOException {
		completedJson(status);
		return ResultEncoder.decode((String) status.getResultParameters().get(PARAM_SERVICE_RESULT));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object o) {
		then(o).isInstanceOf(Map.class);
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		then(o).isInstanceOf(List.class);
		return (List<Map<String, Object>>) o;
	}

	private static long num(Object o) {
		then(o).isInstanceOf(Number.class);
		return ((Number) o).longValue();
	}

	private static Path write(Path path, String content) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, content);
		return path;
	}

	private void copyLog(String resource, String name, String mtime) throws IOException {
		Path dest = paths.getLogDir().resolve(name);
		Files.createDirectories(dest.getParent());
		try (InputStream in = getClass().getResourceAsStream(resource)) {
			then(in).as("Resource %s exists", resource).isNotNull();
			Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
		}
		Files.setLastModifiedTime(dest, FileTime.from(Instant.parse(mtime)));
	}

	private void defaultLogs() throws IOException {
		copyLog("solarnode-default.log", "solarnode.log", "2026-10-15T11:55:01.123Z");
		copyLog("solarnode-default-1.log", "solarnode-1.log", "2026-10-15T05:55:00Z");
	}

	private static String logHeader(Instant ts, String level, String logger) {
		String col = (logger.length() > 40 ? logger.substring(logger.length() - 40) : logger);
		return LOG_TS.format(LocalDateTime.ofInstant(ts, ZONE)) + " " + String.format("%-5s", level)
				+ " " + String.format("%40s", col) + "; ";
	}

	/**
	 * Assert that no fragment of any secret appears in a result.
	 *
	 * @param json
	 *        the result JSON
	 * @param secrets
	 *        the planted secrets
	 * @param benign
	 *        the log text with all secrets removed; fragments that also appear
	 *        here (for example {@literal "correc"} in {@literal "incorrect"})
	 *        are not secret
	 */
	private static void thenNoSecretLeaks(String json, List<String> secrets, String benign) {
		for ( String secret : secrets ) {
			for ( int i = 0; i + MIN_SECRET_LENGTH <= secret.length(); i++ ) {
				String part = secret.substring(i, i + MIN_SECRET_LENGTH);
				if ( benign.contains(part) ) {
					continue;
				}
				then(json).as("Secret [%s] fragment [%s] not in result", secret, part)
						.doesNotContain(part);
			}
		}
	}

	@Test
	public void capabilities() throws IOException {
		// GIVEN
		defaultLogs();
		replayAll();

		// WHEN
		InstructionStatus status = exec(DiagnosticsService.CAPABILITIES_OP);

		// THEN
		Map<String, Object> env = completed(status);
		// @formatter:off
		then(env)
			.containsEntry("v", 1)
			.containsEntry("op", "capabilities")
			.containsEntry("opV", 1)
			.containsEntry("plugin", "dev")
			.containsEntry("node", Map.of("nowMs", NOW.toEpochMilli(), "zone", "Z"))
			.containsEntry("truncated", List.of())
			.containsEntry("notAvailable", List.of())
			;
		Map<String, Object> data = map(env.get("data"));
		then(data)
			.containsEntry("api", 1)
			.containsEntry("ops", Map.of("logs", 1, "storage", 1, "system", 1))
			.containsEntry("disabledOps", List.of())
			.containsEntry("stuckOps", List.of())
			;
		then(data)
			.extractingByKey("logs", MAP)
			.containsEntry("supported", true)
			.containsEntry("layout", "default")
			.extractingByKey("files", LIST)
			.hasSize(2)
			;
		// @formatter:on
	}

	@Test
	public void system() throws Exception {
		// GIVEN
		Path proc = paths.getProcDir();
		write(proc.resolve("meminfo"), "MemTotal:        3884820 kB\nMemAvailable:    2000000 kB\n"
				+ "SwapTotal:        102396 kB\nSwapFree:          51198 kB\n");
		write(proc.resolve("uptime"), "12345.67 45678.90\n");
		write(proc.resolve("loadavg"), "0.52 0.58 0.59 1/389 12345\n");
		write(proc.resolve("sys/kernel/osrelease"), "6.1.0-rpi7-rpi-v8\n");
		write(paths.getOsReleaseFile(),
				"ID=debian\nVERSION_ID=\"12\"\nSECRET=" + FILE_CONTENT_SECRET + "\n");

		expect(platformServiceRef.service()).andReturn(platformService);
		expect(platformService.activePlatformState()).andReturn(PlatformState.Normal);
		expect(bundleContext.getAllServiceReferences(OsgiRuntimeInfo.DATABASE_SYSTEM_SERVICE_CLASS,
				OsgiRuntimeInfo.NODE_DB_FILTER)).andReturn(null);
		expect(bundleContext.getBundles()).andReturn(new Bundle[0]);
		replayAll();

		// WHEN
		InstructionStatus status = exec(SystemOp.NAME);

		// THEN
		String json = completedJson(status);
		then(json).as("Non-allowlisted os-release values not returned")
				.doesNotContain(FILE_CONTENT_SECRET);
		Map<String, Object> env = completed(status);
		// @formatter:off
		then(env)
			.containsEntry("op", "system")
			.containsEntry("opV", 1)
			.containsEntry("notAvailable", List.of())
			;
		then(map(env.get("data")))
			.containsKeys("os", "kernel", "arch", "cpus", "java", "jvm", "memory", "uptime",
					"loadAvg", "platform", "db", "bundles")
			.containsEntry("os", Map.of("id", "debian", "versionId", "12"))
			.containsEntry("kernel", "6.1.0-rpi7-rpi-v8")
			.containsEntry("platform", Map.of("state", "Normal"))
			.containsEntry("db", Map.of("backend", OsgiRuntimeInfo.BACKEND_UNKNOWN))
			;
		then((List<?>) map(env.get("data")).get("loadAvg"))
			.extracting(n -> ((Number) n).doubleValue())
			.containsExactly(0.52, 0.58, 0.59)
			;
		// @formatter:on
	}

	@Test
	public void storage() throws Exception {
		// GIVEN
		Path dbDir = paths.getDbDirs().get(0);
		write(dbDir.resolve("solarnode.mv.db"), "x".repeat(100));
		write(paths.getSettingsBackupDir().resolve("settings_1.txt"),
				"mqttPassword=" + FILE_CONTENT_SECRET + "\n");
		write(paths.getPersistLogDir().resolve("solarnode-settings-audit.log"),
				"apiKey=" + FILE_CONTENT_SECRET + "\n");
		defaultLogs();
		Files.createDirectories(paths.getHomeDir());
		Files.createDirectories(paths.getRamDir());

		expect(bundleContext.getAllServiceReferences(OsgiRuntimeInfo.DATABASE_SYSTEM_SERVICE_CLASS,
				OsgiRuntimeInfo.NODE_DB_FILTER)).andReturn(null);
		replayAll();

		// WHEN
		InstructionStatus status = exec(StorageOp.NAME);

		// THEN
		String json = completedJson(status);
		then(json).as("File contents never returned").doesNotContain(FILE_CONTENT_SECRET)
				.doesNotContain("mqttPassword").doesNotContain("apiKey");
		Map<String, Object> env = completed(status);
		then(env).containsEntry("op", "storage").containsEntry("truncated", List.of());
		Map<String, Object> data = map(env.get("data"));
		// @formatter:off
		then(data)
			.containsKeys("mounts", "db", "settingsBackups", "logs", "auditLog")
			;
		then(map(data.get("db")))
			.containsEntry("backend", OsgiRuntimeInfo.BACKEND_UNKNOWN)
			.extractingByKey("dirs", LIST)
			.hasSize(1)
			;
		then(list(map(list(map(data.get("db")).get("dirs")).get(0)).get("files")))
			.extracting(m -> m.get("name"), m -> num(m.get("sizeBytes")))
			.containsExactly(tuple("solarnode.mv.db", 100L))
			;
		then(map(data.get("settingsBackups")))
			.containsEntry("count", 1)
			;
		then(list(map(data.get("logs")).get("files")))
			.extracting(m -> m.get("name"))
			.containsExactly("solarnode.log", "solarnode-1.log")
			;
		then(list(map(data.get("auditLog")).get("files")))
			.extracting(m -> m.get("name"))
			.containsExactly("solarnode-settings-audit.log")
			;
		then(list(env.get("notAvailable")))
			.as("Missing mount points and backups reported as absent, paths not masked")
			.extracting(m -> m.get("item"), m -> m.get("reason"))
			.contains(
				tuple("mounts:" + tmp.getRoot().toPath().resolve("tmp"), Reason.ABSENT.key()),
				tuple("dbBackups", Reason.ABSENT.key()))
			;
		// @formatter:on
	}

	@Test
	public void logs() throws Exception {
		// GIVEN
		defaultLogs();
		replayAll();

		// WHEN
		InstructionStatus status = exec(LogsOp.NAME);

		// THEN
		Map<String, Object> env = completed(status);
		// @formatter:off
		then(env)
			.containsEntry("op", "logs")
			.containsEntry("opV", 1)
			.containsEntry("truncated", List.of())
			.containsEntry("notAvailable", List.of())
			;
		Map<String, Object> data = map(env.get("data"));
		then(data)
			.containsEntry("layout", "default")
			;
		then(map(data.get("scanned")))
			.containsEntry("files", 2)
			.containsEntry("complete", true)
			;
		then(list(data.get("summaries")))
			.filteredOn(m -> INV_PATTERN.equals(m.get("pattern")))
			.singleElement()
			.satisfies(m -> then(m)
				.containsEntry("level", "ERROR")
				.containsEntry("logger", MODBUS_LOGGER)
				.containsEntry("count", 96)
				.containsEntry("targets", List.of(Map.of("value", "192.168.20.33:502", "count", 96)))
				.containsEntry("sources", List.of(Map.of("value", "/SITE-C/INV/3", "count", 96)))
				.containsEntry("exception", Map.of("root", "java.net.NoRouteToHostException")))
			;
		// @formatter:on
		then(completedJson(status)).as("Continuation and audit text never output")
				.doesNotContain("hunter2").doesNotContain("AUDIT");
	}

	@Test
	public void logs_untilAfterNow_invalidParameter() throws Exception {
		// GIVEN
		defaultLogs();
		replayAll();

		final List<InstructionStatus> statuses = new ArrayList<>(8);
		for ( int i = 0; i < 4; i++ ) {
			// WHEN
			statuses.add(exec(LogsOp.NAME, "untilMs",
					String.valueOf(NOW.toEpochMilli() + TimeUnit.DAYS.toMillis(1))));
			statuses.add(exec(LogsOp.NAME, "sinceMs",
					String.valueOf(NOW.toEpochMilli() - TimeUnit.DAYS.toMillis(20))));
		}
		InstructionStatus caps = exec(DiagnosticsService.CAPABILITIES_OP);

		// THEN
		for ( int i = 0; i < statuses.size(); i++ ) {
			InstructionStatus status = statuses.get(i);
			// @formatter:off
			then(status)
				.isNotNull()
				.returns(InstructionState.Declined, InstructionStatus::getInstructionState)
				;
			then(map(status.getResultParameters()))
				.as("Time-dependent parameter failure declined as invalid parameter")
				.containsEntry("code", DiagnosticsService.ERROR_INVALID_PARAMETER)
				.containsEntry(PARAM_MESSAGE, i % 2 == 0 ? "Invalid parameter: untilMs"
						: "Invalid parameter: sinceMs")
				;
			// @formatter:on
		}
		// @formatter:off
		then(map(completed(caps).get("data")))
			.extractingByKey("rate", MAP)
			.extractingByKey("scan", MAP)
			.as("No scan rate tokens used by invalid requests")
			.containsEntry("available", 2)
			;
		// @formatter:on
	}

	private static final class CorpusEntry {

		private final List<String> secrets;
		private final String message;

		private CorpusEntry(List<String> secrets, String message) {
			super();
			this.secrets = secrets;
			this.message = message;
		}

	}

	private List<CorpusEntry> corpus() throws IOException {
		List<CorpusEntry> result = new ArrayList<>(64);
		try (InputStream in = getClass().getResourceAsStream("redact-corpus.txt");
				BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF_8))) {
			String l;
			while ( (l = r.readLine()) != null ) {
				if ( l.isBlank() || l.startsWith("#") ) {
					continue;
				}
				int tab = l.indexOf('\t');
				result.add(new CorpusEntry(Arrays.asList(l.substring(0, tab).split(",")),
						l.substring(tab + 1)));
			}
		}
		return result;
	}

	@Test
	public void logs_plantedSecretsRedacted() throws Exception {
		// GIVEN
		final List<CorpusEntry> corpus = corpus();
		final List<String> secrets = new ArrayList<>(64);
		final StringBuilder benign = new StringBuilder();
		final Instant base = NOW.minus(2, ChronoUnit.HOURS);
		final Path logFile = paths.getLogDir().resolve("solarnode.log");
		Files.createDirectories(logFile.getParent());
		try (Writer w = Files.newBufferedWriter(logFile, UTF_8)) {
			for ( int i = 0; i < corpus.size(); i++ ) {
				CorpusEntry e = corpus.get(i);
				secrets.addAll(e.secrets);
				String msg = e.message;
				for ( String secret : e.secrets ) {
					msg = msg.replace(secret, "\u0000");
				}
				benign.append(msg).append('\u0000');
				Instant ts = base.plusSeconds(i * 60L);
				String secretList = String.join(" ", e.secrets);
				w.write(logHeader(ts, (i % 3 == 0 ? "ERROR" : "WARN"),
						CORPUS_LOGGERS[i % CORPUS_LOGGERS.length]) + e.message + "\n");
				w.write("net.solarnetwork.service.RemoteServiceException: " + e.message + "\n");
				w.write("\tat net.solarnetwork.node.Foo.bar(Foo.java:1)\n");
				w.write("Caused by: java.lang.IllegalStateException: " + secretList + "\n");
				w.write("\t... 3 more\n");
				w.write(logHeader(ts.plusSeconds(1), "WARN", AUDIT_LOGGER) + e.message + "\n");
			}
		}
		final int pageSize = 8;
		replayAll();

		// WHEN
		long totalMatched = 0;
		for ( int page = 0; page * pageSize < corpus.size(); page++ ) {
			long since = base.plusSeconds(page * pageSize * 60L - 30L).toEpochMilli();
			long until = base.plusSeconds((page + 1) * pageSize * 60L - 30L).toEpochMilli();
			int expected = Math.min(pageSize, corpus.size() - page * pageSize);
			advanceOneHour();
			InstructionStatus status = exec(LogsOp.NAME, "sinceMs", String.valueOf(since), "untilMs",
					String.valueOf(until), "limit", "30");

			// THEN
			String json = completedJson(status);
			thenNoSecretLeaks(json, secrets, benign.toString());
			then(json).as("Audit logger entries dropped").doesNotContain("AUDIT");

			Map<String, Object> env = completed(status);
			// @formatter:off
			then(env)
				.as("Page %d not truncated", page)
				.containsEntry("truncated", List.of())
				.containsEntry("notAvailable", List.of())
				;
			then(num(env.get("redacted"))).as("Page %d redaction count", page).isPositive();
			// @formatter:on
			Map<String, Object> data = map(env.get("data"));
			long matched = num(map(data.get("scanned")).get("matched"));
			then(matched).as("Page %d matched entries", page).isEqualTo(expected);
			long summarised = list(data.get("summaries")).stream().mapToLong(m -> num(m.get("count")))
					.sum() + num(map(data.get("otherPatterns")).get("entries"));
			then(summarised).as("Page %d every matched entry summarised", page).isEqualTo(matched);
			then(list(data.get("summaries"))).as("Page %d root exceptions are class names only", page)
					.allSatisfy(m -> then(m).containsEntry("exception",
							Map.of("root", "java.lang.IllegalStateException")));
			totalMatched += matched;
		}

		// THEN
		then(totalMatched).as("All corpus entries scanned").isEqualTo(corpus.size());
	}

	private static String word(Random rng) {
		final String letters = "bcdfghjklmnpqrstvwxz";
		int len = 4 + rng.nextInt(6);
		StringBuilder buf = new StringBuilder(len);
		for ( int i = 0; i < len; i++ ) {
			buf.append(letters.charAt(rng.nextInt(letters.length())));
		}
		return buf.toString();
	}

	@Test
	public void logs_largeLogWithinBudget() throws Exception {
		// GIVEN
		final int lineCount = 50_000;
		final Random rng = new Random(20261016L);
		final List<String> templates = new ArrayList<>(2000);
		for ( int i = 0; i < 2000; i++ ) {
			StringBuilder buf = new StringBuilder();
			for ( int j = 0, n = 6 + rng.nextInt(8); j < n; j++ ) {
				if ( j > 0 ) {
					buf.append(' ');
				}
				buf.append(word(rng));
			}
			templates.add(buf.toString());
		}
		final Instant base = NOW.minus(20, ChronoUnit.HOURS);
		final Path logFile = paths.getLogDir().resolve("solarnode.log");
		Files.createDirectories(logFile.getParent());
		try (Writer w = Files.newBufferedWriter(logFile, UTF_8)) {
			for ( int i = 0; i < lineCount; i++ ) {
				int r = rng.nextInt(10);
				String level = (r < 4 ? "INFO" : r < 8 ? "WARN" : "ERROR");
				w.write(logHeader(base.plusSeconds(i), level,
						CORPUS_LOGGERS[rng.nextInt(CORPUS_LOGGERS.length)]));
				w.write(templates.get(rng.nextInt(templates.size())));
				w.write(" device 10.0.0." + rng.nextInt(255) + ":502 count " + i + "\n");
			}
		}
		service.setLogsDeadlineMs(60_000L);
		service.setMaxScanBytes(64L * 1024L * 1024L);
		replayAll();

		// WHEN
		InstructionStatus status = exec(LogsOp.NAME, "limit", "30");

		// THEN
		String json = completedJson(status);
		then(json).as("Decoded JSON").startsWith("{");
		Map<String, Object> env = completed(status);
		then(env).containsEntry("notAvailable", List.of());
		Map<String, Object> data = map(env.get("data"));
		// @formatter:off
		then(map(data.get("scanned")))
			.containsEntry("lines", lineCount)
			.containsEntry("complete", true)
			;
		then(num(map(data.get("otherPatterns")).get("keys"))).as("Patterns beyond limit")
			.isPositive();
		// @formatter:on
		List<Map<String, Object>> summaries = list(data.get("summaries"));
		List<Map<String, Object>> truncated = list(env.get("truncated"));
		if ( truncated.isEmpty() ) {
			then(summaries).as("Complete summaries when not truncated").hasSize(30);
		} else {
			// @formatter:off
			then(truncated)
				.as("Summaries truncated to fit budget")
				.anySatisfy(t -> then(t)
					.containsEntry("path", "data.summaries")
					.containsEntry("total", 30)
					.containsEntry("kept", summaries.size())
					.containsEntry("reason", "budget"))
				;
			// @formatter:on
			then(summaries.size()).as("Some summaries kept").isBetween(1, 29);
		}
	}

}
