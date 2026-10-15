/* ==================================================================
 * SystemOpTests.java - 16/10/2026 9:00:00 am
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
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.easymock.EasyMock.expect;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;
import net.solarnetwork.node.service.PlatformService;
import net.solarnetwork.node.service.PlatformService.PlatformState;
import net.solarnetwork.node.system.diagnostics.Deadline;
import net.solarnetwork.node.system.diagnostics.DiagnosticOp;
import net.solarnetwork.node.system.diagnostics.DiagnosticResult;
import net.solarnetwork.node.system.diagnostics.DiagnosticsPaths;
import net.solarnetwork.node.system.diagnostics.OpContext;
import net.solarnetwork.node.system.diagnostics.OsgiRuntimeInfo;
import net.solarnetwork.node.system.diagnostics.Redactor;
import net.solarnetwork.node.system.diagnostics.SystemOp;
import net.solarnetwork.service.StaticOptionalService;

/**
 * Test cases for the {@link SystemOp} class.
 *
 * @author elijah
 * @version 1.0
 */
public class SystemOpTests {

	// @formatter:off
	private static final String MEMINFO =
			"MemTotal:        3884820 kB\n"
			+ "MemFree:          123456 kB\n"
			+ "MemAvailable:    2000000 kB\n"
			+ "Buffers:           10000 kB\n"
			+ "SwapTotal:        102396 kB\n"
			+ "SwapFree:          51198 kB\n"
			+ "HugePages_Total:       0\n";

	private static final String OS_RELEASE =
			"PRETTY_NAME=\"Debian GNU/Linux 12 (bookworm)\"\n"
			+ "NAME=\"Debian GNU/Linux\"\n"
			+ "VERSION_ID=\"12\"\n"
			+ "VERSION=\"12 (bookworm)\"\n"
			+ "VERSION_CODENAME=bookworm\n"
			+ "ID=debian\n"
			+ "HOME_URL=\"https://www.debian.org/\"\n"
			+ "SECRET_THING=\"should-not-appear\"\n";
	// @formatter:on

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private BundleContext bundleContext;
	private PlatformService platformService;
	private List<Object> mocks;
	private Path root;
	private DiagnosticsPaths paths;

	@Before
	public void setup() {
		mocks = new ArrayList<>(8);
		bundleContext = mock(BundleContext.class);
		platformService = mock(PlatformService.class);
		root = tmp.getRoot().toPath();
		paths = DiagnosticsPaths.rootedAt(root);
	}

	@After
	public void teardown() {
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

	private static OpContext context(Deadline deadline) {
		return new OpContext(Map.of(), Instant.now(), ZoneOffset.UTC, deadline, new Redactor(),
				1024L * 1024L);
	}

	private void write(Path path, String content) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, content);
	}

	private void writeProcFiles() throws IOException {
		write(paths.getProcDir().resolve("meminfo"), MEMINFO);
		write(paths.getProcDir().resolve("uptime"), "12345.67 45678.90\n");
		write(paths.getProcDir().resolve("loadavg"), "0.52 0.58 0.59 1/389 12345\n");
		write(paths.getProcDir().resolve("sys/kernel/osrelease"), "6.1.0-rpi7-rpi-v8\n");
		write(paths.getOsReleaseFile(), OS_RELEASE);
	}

	private void expectDb(String name) throws InvalidSyntaxException {
		ServiceReference<?> ref = mock(ServiceReference.class);
		Bundle dbBundle = mock(Bundle.class);
		expect(bundleContext.getAllServiceReferences(OsgiRuntimeInfo.DATABASE_SYSTEM_SERVICE_CLASS,
				OsgiRuntimeInfo.NODE_DB_FILTER)).andReturn(new ServiceReference<?>[] { ref });
		expect(ref.getBundle()).andReturn(dbBundle);
		expect(dbBundle.getSymbolicName()).andReturn(name);
		expect(dbBundle.getVersion()).andReturn(new Version(4, 0, 0));
	}

	private void expectBundles() {
		Bundle active = mock(Bundle.class);
		expect(active.getState()).andReturn(Bundle.ACTIVE);
		Bundle resolved = mock(Bundle.class);
		expect(resolved.getState()).andReturn(Bundle.RESOLVED);
		expect(resolved.getHeaders("")).andReturn(new Hashtable<>());
		expect(resolved.getSymbolicName()).andReturn("net.solarnetwork.node.foo");
		expect(resolved.getVersion()).andReturn(new Version(1, 0, 0));
		Bundle fragment = mock(Bundle.class);
		expect(fragment.getState()).andReturn(Bundle.RESOLVED);
		Hashtable<String, String> fragHeaders = new Hashtable<>();
		fragHeaders.put("Fragment-Host", "net.solarnetwork.node");
		expect(fragment.getHeaders("")).andReturn(fragHeaders);
		expect(bundleContext.getBundles()).andReturn(new Bundle[] { active, resolved, fragment });
	}

	private static Map<String, Object> notAvailable(String item, DiagnosticResult.Reason reason) {
		return Map.of("item", item, "reason", reason.key());
	}

	@Test
	public void info() {
		// GIVEN
		SystemOp op = new SystemOp(bundleContext, new StaticOptionalService<>(platformService), paths);

		// THEN
		then(op.name()).as("Name").isEqualTo("system");
		then(op.version()).as("Version").isEqualTo(1);
		then(op.rateClass()).as("Rate class").isEqualTo(DiagnosticOp.RateClass.LIGHT);
		replayAll();
	}

	@Test
	public void run() throws Exception {
		// GIVEN
		writeProcFiles();
		expect(platformService.activePlatformState()).andReturn(PlatformState.Normal);
		expectDb("net.solarnetwork.node.dao.jdbc.h2");
		expectBundles();

		SystemOp op = new SystemOp(bundleContext, new StaticOptionalService<>(platformService), paths);

		// WHEN
		replayAll();
		DiagnosticResult result = op.run(context(Deadline.ofMillis(60_000L)));

		// THEN
		then(result.getNotAvailable()).as("All items available").isEmpty();
		// @formatter:off
		then(result.getData())
			.as("Data keys")
			.containsOnlyKeys("os", "kernel", "arch", "cpus", "java", "jvm", "memory", "uptime",
					"loadAvg", "platform", "db", "bundles")
			.as("Allowlisted os-release keys, quotes stripped")
			.containsEntry("os", Map.of("id", "debian", "versionId", "12",
					"versionCodename", "bookworm", "prettyName", "Debian GNU/Linux 12 (bookworm)"))
			.as("Kernel trimmed")
			.containsEntry("kernel", "6.1.0-rpi7-rpi-v8")
			.containsEntry("arch", System.getProperty("os.arch"))
			.containsEntry("cpus", Runtime.getRuntime().availableProcessors())
			.as("Memory parsed as bytes")
			.containsEntry("memory", Map.of("totalBytes", 3884820L * 1024L,
					"availableBytes", 2000000L * 1024L, "swapTotalBytes", 102396L * 1024L,
					"swapFreeBytes", 51198L * 1024L))
			.as("Load average parsed")
			.containsEntry("loadAvg", List.of(0.52, 0.58, 0.59))
			.as("Platform state")
			.containsEntry("platform", Map.of("state", "Normal"))
			.as("DB backend")
			.containsEntry("db", Map.of("backend", "h2", "bundle", "net.solarnetwork.node.dao.jdbc.h2",
					"bundleVersion", "4.0.0"))
			;
		then(result.getData())
			.extractingByKey("uptime", MAP)
			.as("System uptime in ms")
			.containsEntry("systemMs", 12345670L)
			.containsKey("jvmMs")
			;
		then(result.getData())
			.extractingByKey("java", MAP)
			.containsEntry("version", System.getProperty("java.version"))
			.containsKeys("vendor", "vmName", "vmVersion")
			;
		then(result.getData())
			.extractingByKey("jvm", MAP)
			.containsKeys("startMs", "uptimeMs", "heap", "nonHeapUsedBytes", "threads", "inputArgs")
			.extractingByKey("inputArgs", LIST)
			.as("JVM input args never include system properties")
			.noneMatch(a -> a.toString().startsWith("-D"))
			;
		then(result.getData())
			.extractingByKey("bundles", MAP)
			.containsEntry("total", 3)
			.containsEntry("notActiveCount", 1)
			.containsEntry("notActive", List.of(Map.of("name", "net.solarnetwork.node.foo",
					"version", "1.0.0", "state", "RESOLVED")))
			;
		// @formatter:on
	}

	@Test
	public void run_missingFiles() throws Exception {
		// GIVEN
		expectDb("net.solarnetwork.node.dao.jdbc.postgres");
		expectBundles();

		SystemOp op = new SystemOp(bundleContext, () -> null, paths);

		// WHEN
		replayAll();
		DiagnosticResult result = op.run(context(Deadline.ofMillis(60_000L)));

		// THEN
		// @formatter:off
		then(result.getNotAvailable())
			.as("Missing files and platform service reported as absent")
			.containsExactly(
					notAvailable("os", DiagnosticResult.Reason.ABSENT),
					notAvailable("kernel", DiagnosticResult.Reason.ABSENT),
					notAvailable("memory", DiagnosticResult.Reason.ABSENT),
					notAvailable("uptime", DiagnosticResult.Reason.ABSENT),
					notAvailable("loadAvg", DiagnosticResult.Reason.ABSENT),
					notAvailable("platform", DiagnosticResult.Reason.ABSENT))
			;
		then(result.getData())
			.as("Absent items not included")
			.doesNotContainKeys("os", "kernel", "memory", "loadAvg", "platform")
			.as("Postgres backend detected")
			.extractingByKey("db", MAP)
			.containsEntry("backend", "postgres")
			;
		then(result.getData())
			.extractingByKey("uptime", MAP)
			.as("JVM uptime still available")
			.containsOnlyKeys("jvmMs")
			;
		// @formatter:on
	}

	@Test
	public void run_missingMeminfo() throws Exception {
		// GIVEN
		writeProcFiles();
		Files.delete(paths.getProcDir().resolve("meminfo"));
		expect(platformService.activePlatformState()).andReturn(PlatformState.UserBlockingSystemTask);
		expectDb("net.solarnetwork.node.dao.jdbc.h2");
		expectBundles();

		SystemOp op = new SystemOp(bundleContext, new StaticOptionalService<>(platformService), paths);

		// WHEN
		replayAll();
		DiagnosticResult result = op.run(context(Deadline.ofMillis(60_000L)));

		// THEN
		// @formatter:off
		then(result.getNotAvailable())
			.as("Memory reported as absent")
			.containsExactly(notAvailable("memory", DiagnosticResult.Reason.ABSENT))
			;
		then(result.getData())
			.doesNotContainKey("memory")
			.containsEntry("platform", Map.of("state", "UserBlockingSystemTask"))
			;
		// @formatter:on
	}

	@Test
	public void run_itemErrors() throws Exception {
		// GIVEN
		writeProcFiles();
		write(paths.getProcDir().resolve("loadavg"), "not numbers here\n");
		expect(platformService.activePlatformState()).andReturn(PlatformState.Normal);
		expect(bundleContext.getAllServiceReferences(OsgiRuntimeInfo.DATABASE_SYSTEM_SERVICE_CLASS,
				OsgiRuntimeInfo.NODE_DB_FILTER)).andThrow(new InvalidSyntaxException("bad", "bad"));
		expect(bundleContext.getBundles()).andThrow(new IllegalStateException("Boom"));

		SystemOp op = new SystemOp(bundleContext, new StaticOptionalService<>(platformService), paths);

		// WHEN
		replayAll();
		DiagnosticResult result = op.run(context(Deadline.ofMillis(60_000L)));

		// THEN
		// @formatter:off
		then(result.getNotAvailable())
			.as("Per-item errors reported, not propagated")
			.containsExactly(
					notAvailable("loadAvg", DiagnosticResult.Reason.ERROR),
					notAvailable("db", DiagnosticResult.Reason.ERROR),
					notAvailable("bundles", DiagnosticResult.Reason.ERROR))
			;
		then(result.getData())
			.as("Other items still collected")
			.containsKeys("os", "memory", "platform")
			.doesNotContainKeys("loadAvg", "db", "bundles")
			;
		// @formatter:on
	}

	@Test
	public void run_osReleaseSymlink() throws Exception {
		// GIVEN
		writeProcFiles();
		final Path etc = paths.getOsReleaseFiles().get(0);
		final Path usr = paths.getOsReleaseFiles().get(1);
		write(usr, OS_RELEASE);
		Files.delete(etc);
		Files.createSymbolicLink(etc, etc.getParent().relativize(usr));
		expect(platformService.activePlatformState()).andReturn(PlatformState.Normal);
		expectDb("net.solarnetwork.node.dao.jdbc.h2");
		expectBundles();

		SystemOp op = new SystemOp(bundleContext, new StaticOptionalService<>(platformService), paths);

		// WHEN
		replayAll();
		DiagnosticResult result = op.run(context(Deadline.ofMillis(60_000L)));

		// THEN
		// @formatter:off
		then(paths.getOsReleaseFiles())
			.as("Standard os-release locations, in order")
			.containsExactly(root.resolve("etc/os-release"), root.resolve("usr/lib/os-release"))
			;
		then(result.getNotAvailable()).as("All items available").isEmpty();
		then(result.getData())
			.as("os-release read from /usr/lib when /etc/os-release is a link")
			.containsEntry("os", Map.of("id", "debian", "versionId", "12",
					"versionCodename", "bookworm", "prettyName", "Debian GNU/Linux 12 (bookworm)"))
			;
		// @formatter:on
	}

	@Test
	public void run_osReleaseSymlinkNotFollowed() throws Exception {
		// GIVEN
		writeProcFiles();
		final Path etc = paths.getOsReleaseFiles().get(0);
		final Path other = root.resolve("var/lib/solarnode/other.txt");
		write(other, "ID=leaked\n");
		Files.delete(etc);
		Files.createSymbolicLink(etc, other);
		expect(platformService.activePlatformState()).andReturn(PlatformState.Normal);
		expectDb("net.solarnetwork.node.dao.jdbc.h2");
		expectBundles();

		SystemOp op = new SystemOp(bundleContext, new StaticOptionalService<>(platformService), paths);

		// WHEN
		replayAll();
		DiagnosticResult result = op.run(context(Deadline.ofMillis(60_000L)));

		// THEN
		// @formatter:off
		then(result.getNotAvailable())
			.as("Linked os-release not followed, and no fallback file")
			.containsExactly(notAvailable("os", DiagnosticResult.Reason.ABSENT))
			;
		then(result.getData()).as("No os data").doesNotContainKey("os");
		// @formatter:on
	}

	@Test
	public void run_deadlineExpired() throws Exception {
		// GIVEN
		writeProcFiles();
		SystemOp op = new SystemOp(bundleContext, new StaticOptionalService<>(platformService), paths);

		// WHEN
		replayAll();
		DiagnosticResult result = op.run(context(new Deadline(0L, () -> 0L)));

		// THEN
		// @formatter:off
		then(result.getNotAvailable())
			.as("Timeout reported")
			.containsExactly(notAvailable("system", DiagnosticResult.Reason.TIMEOUT))
			;
		then(result.getData()).as("No data collected").isEmpty();
		// @formatter:on
	}

	@Test
	public void run_deadlinePartial() throws Exception {
		// GIVEN
		writeProcFiles();
		final long[] now = new long[] { 0L };
		// expire after a few deadline checks
		Deadline deadline = new Deadline(1L, () -> now[0]++ < 4 ? 0L : Long.MAX_VALUE / 2);
		SystemOp op = new SystemOp(bundleContext, new StaticOptionalService<>(platformService), paths);

		// WHEN
		replayAll();
		DiagnosticResult result = op.run(context(deadline));

		// THEN
		// @formatter:off
		then(result.getNotAvailable())
			.as("Timeout reported")
			.containsExactly(notAvailable("system", DiagnosticResult.Reason.TIMEOUT))
			;
		then(result.getData())
			.as("Partial data returned")
			.containsOnlyKeys("os", "kernel", "arch")
			;
		// @formatter:on
	}

	@Test
	public void parseOsRelease_cut() {
		// GIVEN
		String text = "PRETTY_NAME='" + "x".repeat(200) + "'\nID=foo\n";

		// WHEN
		Map<String, Object> result = SystemOp.parseOsRelease(text);

		// THEN
		// @formatter:off
		then(result)
			.as("Values cut to max length, single quotes stripped")
			.containsOnly(Map.entry("id", "foo"), Map.entry("prettyName", "x".repeat(100)))
			;
		// @formatter:on
		replayAll();
	}

	@Test
	public void filterInputArgs() {
		// GIVEN
		// @formatter:off
		List<String> args = List.of(
				"-Xmx512m",
				"-Xms64M",
				"-Xss1m",
				"-Dsn.home=/var/lib/solarnode",
				"-Dsecret.password=abc123",
				"-XX:+UseG1GC",
				"-XX:-UseSerialGC",
				"-XX:MaxRAMPercentage=75.0",
				"-XX:MaxMetaspaceSize=128m",
				"-XX:+HeapDumpOnOutOfMemoryError",
				"-javaagent:/tmp/agent.jar",
				"-Xmx512m -Dfoo=bar");
		// @formatter:on

		// WHEN
		List<String> result = SystemOp.filterInputArgs(args);

		// THEN
		// @formatter:off
		then(result)
			.as("Only allowlisted arguments returned")
			.containsExactly("-Xmx512m", "-Xms64M", "-Xss1m", "-XX:+UseG1GC", "-XX:-UseSerialGC",
					"-XX:MaxRAMPercentage=75.0", "-XX:MaxMetaspaceSize=128m")
			;
		// @formatter:on
		replayAll();
	}

}
