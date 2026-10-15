/* ==================================================================
 * StorageOpTests.java - 16/10/2026 9:00:00 am
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
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import net.solarnetwork.node.system.diagnostics.Deadline;
import net.solarnetwork.node.system.diagnostics.DiagnosticOp;
import net.solarnetwork.node.system.diagnostics.DiagnosticResult;
import net.solarnetwork.node.system.diagnostics.DiagnosticsPaths;
import net.solarnetwork.node.system.diagnostics.OpContext;
import net.solarnetwork.node.system.diagnostics.OsgiRuntimeInfo;
import net.solarnetwork.node.system.diagnostics.Redactor;
import net.solarnetwork.node.system.diagnostics.StorageOp;

/**
 * Test cases for the {@link StorageOp} class.
 *
 * @author elijah
 * @version 1.0
 */
public class StorageOpTests {

	private static final long T0 = 1760000000000L;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private BundleContext bundleContext;
	private List<Object> mocks;
	private Path root;
	private DiagnosticsPaths paths;

	@Before
	public void setup() {
		mocks = new ArrayList<>(8);
		bundleContext = mock(BundleContext.class);
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

	private static Path file(Path path, int size, long mtime) throws IOException {
		Files.createDirectories(path.getParent());
		Files.write(path, new byte[size]);
		Files.setLastModifiedTime(path, FileTime.fromMillis(mtime));
		return path;
	}

	private static Map<String, Object> info(String name, long size, long mtime) {
		return Map.of("name", name, "sizeBytes", size, "mtimeMs", mtime);
	}

	private static Map<String, Object> notAvailable(String item, DiagnosticResult.Reason reason) {
		return Map.of("item", item, "reason", reason.key());
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

	/**
	 * Create all mount points except {@code /tmp}.
	 */
	private void createMounts() throws IOException {
		for ( Path p : paths.getMountPoints() ) {
			if ( !p.getFileName().toString().equals("tmp") ) {
				Files.createDirectories(p);
			}
		}
	}

	private Path tmpMount() {
		for ( Path p : paths.getMountPoints() ) {
			if ( p.getFileName().toString().equals("tmp") ) {
				return p;
			}
		}
		throw new AssertionError("No /tmp mount");
	}

	private void createFixture() throws IOException {
		createMounts();

		// DB
		Path dbDir = paths.getHomeDir().resolve("var/db");
		file(dbDir.resolve("solarnode.mv.db"), 100, T0);
		file(dbDir.resolve("solarnode-data.mv.db"), 50, T0 + 1);
		file(dbDir.resolve("notes.txt"), 10, T0);
		file(dbDir.resolve("solarnode-reset.1760000000000/solarnode.mv.db"), 300, T0 - 10);
		file(dbDir.resolve("solarnode-reset.1760000000000/solarnode.trace.db"), 30, T0 - 10);
		file(dbDir.resolve("foo-reset.123/solarnode.mv.db"), 999, T0);

		// DB backups
		file(paths.getDbBackupDir().resolve("backup-1.zip"), 11, T0 - 2000);
		file(paths.getDbBackupDir().resolve("backup-2.zip"), 22, T0 - 1000);
		file(paths.getDbBackupDir().resolve("readme.txt"), 33, T0);

		// settings backups: unreadable contents to prove stat-only access
		for ( int i = 1; i <= 3; i++ ) {
			Path p = file(paths.getSettingsBackupDir().resolve("settings_" + i + ".txt"), i * 10,
					T0 + i * 1000);
			Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("---------"));
		}
		file(paths.getSettingsBackupDir().resolve("other.txt"), 1, T0);

		// logs
		file(paths.getLogDir().resolve("solarnode.log"), 500, T0 + 5000);
		file(paths.getLogDir().resolve("solarnode-1.log"), 400, T0 + 4000);
		file(paths.getLogDir().resolve("solarnode-2.log.gz"), 40, T0 + 3000);
		file(paths.getLogDir().resolve("other.log"), 1, T0);

		// audit logs
		Path audit = file(paths.getPersistLogDir().resolve("solarnode-settings-audit.log"), 77,
				T0 + 100);
		Files.setPosixFilePermissions(audit, PosixFilePermissions.fromString("---------"));
	}

	private static final class TestFileStore extends FileStore {

		@Override
		public String name() {
			return "test";
		}

		@Override
		public String type() {
			return "ext4";
		}

		@Override
		public boolean isReadOnly() {
			return false;
		}

		@Override
		public long getTotalSpace() throws IOException {
			return 1000L;
		}

		@Override
		public long getUsableSpace() throws IOException {
			return 400L;
		}

		@Override
		public long getUnallocatedSpace() throws IOException {
			return 500L;
		}

		@Override
		public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) {
			return false;
		}

		@Override
		public boolean supportsFileAttributeView(String name) {
			return false;
		}

		@Override
		public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) {
			return null;
		}

		@Override
		public Object getAttribute(String attribute) throws IOException {
			throw new UnsupportedOperationException();
		}

	}

	@Test
	public void info() {
		// GIVEN
		StorageOp op = new StorageOp(bundleContext, paths);

		// THEN
		then(op.name()).as("Name").isEqualTo("storage");
		then(op.version()).as("Version").isEqualTo(1);
		then(op.rateClass()).as("Rate class").isEqualTo(DiagnosticOp.RateClass.LIGHT);
		replayAll();
	}

	@Test
	public void run() throws Exception {
		// GIVEN
		createFixture();
		expectDb("net.solarnetwork.node.dao.jdbc.h2");

		StorageOp op = new StorageOp(bundleContext, paths, p -> new TestFileStore());

		// WHEN
		replayAll();
		DiagnosticResult result = op.run(context(Deadline.ofMillis(60_000L)));

		// THEN
		// @formatter:off
		then(result.getNotAvailable())
			.as("Missing /tmp mount reported as absent")
			.containsExactly(notAvailable("mounts:" + tmpMount(), DiagnosticResult.Reason.ABSENT))
			;
		then(result.getTruncated()).as("Nothing truncated").isEmpty();
		then(result.getData())
			.as("Data keys")
			.containsOnlyKeys("mounts", "db", "dbBackups", "settingsBackups", "logs", "auditLog")
			;

		then(result.getData())
			.extractingByKey("mounts", LIST)
			.as("Existing mounts reported")
			.hasSize(paths.getMountPoints().size() - 1)
			.first()
			.isEqualTo(Map.of("path", root.toString(), "type", "ext4", "totalBytes", 1000L,
					"usableBytes", 400L))
			;

		then(result.getData())
			.extractingByKey("db", MAP)
			.as("DB backend")
			.containsEntry("backend", "h2")
			.as("Only existing DB directories listed")
			.containsEntry("dirs", List.of(Map.of(
					"path", paths.getHomeDir().resolve("var/db").toString(),
					"files", List.of(
							info("solarnode-data.mv.db", 50L, T0 + 1),
							info("solarnode.mv.db", 100L, T0)),
					"resetBackups", List.of(Map.of(
							"name", "solarnode-reset.1760000000000",
							"takenMs", 1760000000000L,
							"sizeBytes", 330L,
							"files", List.of(
									info("solarnode.mv.db", 300L, T0 - 10),
									info("solarnode.trace.db", 30L, T0 - 10)))))))
			;

		then(result.getData())
			.as("DB backups newest first, zip files only")
			.containsEntry("dbBackups", List.of(
					info("backup-2.zip", 22L, T0 - 1000),
					info("backup-1.zip", 11L, T0 - 2000)))
			;

		then(result.getData())
			.as("Settings backups stat only, newest first")
			.containsEntry("settingsBackups", Map.of(
					"count", 3,
					"totalBytes", 60L,
					"newestMs", T0 + 3000,
					"oldestMs", T0 + 1000,
					"files", List.of(
							info("settings_3.txt", 30L, T0 + 3000),
							info("settings_2.txt", 20L, T0 + 2000),
							info("settings_1.txt", 10L, T0 + 1000))))
			;

		then(result.getData())
			.as("Log files")
			.containsEntry("logs", Map.of(
					"dir", paths.getLogDir().toString(),
					"totalBytes", 940L,
					"files", List.of(
							info("solarnode.log", 500L, T0 + 5000),
							info("solarnode-1.log", 400L, T0 + 4000),
							info("solarnode-2.log.gz", 40L, T0 + 3000))))
			;

		then(result.getData())
			.as("Audit log files stat only")
			.containsEntry("auditLog", Map.of(
					"dir", paths.getPersistLogDir().toString(),
					"totalBytes", 77L,
					"files", List.of(info("solarnode-settings-audit.log", 77L, T0 + 100))))
			;
		// @formatter:on
	}

	@Test
	public void run_emptyRoot() throws Exception {
		// GIVEN
		expect(bundleContext.getAllServiceReferences(OsgiRuntimeInfo.DATABASE_SYSTEM_SERVICE_CLASS,
				OsgiRuntimeInfo.NODE_DB_FILTER)).andReturn(null);
		paths = DiagnosticsPaths.rootedAt(root.resolve("missing"));

		StorageOp op = new StorageOp(bundleContext, paths);

		// WHEN
		replayAll();
		DiagnosticResult result = op.run(context(Deadline.ofMillis(60_000L)));

		// THEN
		List<String> absent = new ArrayList<>();
		for ( Path p : paths.getMountPoints() ) {
			absent.add("mounts:" + p);
		}
		absent.addAll(List.of("dbBackups", "settingsBackups", "logs", "auditLog"));
		// @formatter:off
		then(result.getNotAvailable())
			.as("Everything absent")
			.extracting(m -> m.get("item"))
			.containsExactlyElementsOf(absent)
			;
		then(result.getNotAvailable())
			.extracting(m -> m.get("reason"))
			.containsOnly("absent")
			;
		then(result.getData())
			.as("Unknown backend, no DB dirs")
			.containsEntry("db", Map.of("backend", "unknown", "dirs", List.of()))
			.doesNotContainKeys("mounts", "dbBackups", "settingsBackups")
			;
		// @formatter:on
	}

	@Test
	public void run_realFileStore() throws Exception {
		// GIVEN
		createMounts();
		expectDb("net.solarnetwork.node.dao.jdbc.postgres");

		StorageOp op = new StorageOp(bundleContext, paths);

		// WHEN
		replayAll();
		DiagnosticResult result = op.run(context(Deadline.ofMillis(60_000L)));

		// THEN
		// @formatter:off
		then(result.getData())
			.extractingByKey("mounts", LIST)
			.as("Existing mounts reported from real file store")
			.hasSize(paths.getMountPoints().size() - 1)
			.allSatisfy(m -> {
				then(m).asInstanceOf(MAP)
					.containsKeys("path", "type", "totalBytes", "usableBytes")
					.extractingByKey("totalBytes")
					.isInstanceOf(Long.class)
					;
			})
			;
		then(result.getData())
			.extractingByKey("db", MAP)
			.as("Postgres backend")
			.containsEntry("backend", "postgres")
			;
		// @formatter:on
	}

	@Test
	public void run_fileStoreError() throws Exception {
		// GIVEN
		createMounts();
		expectDb("net.solarnetwork.node.dao.jdbc.h2");

		StorageOp op = new StorageOp(bundleContext, paths, p -> {
			throw new IOException("Boom");
		});

		// WHEN
		replayAll();
		DiagnosticResult result = op.run(context(Deadline.ofMillis(60_000L)));

		// THEN
		List<Map<String, Object>> expected = new ArrayList<>();
		for ( Path p : paths.getMountPoints() ) {
			expected.add(
					notAvailable("mounts:" + p, p.equals(tmpMount()) ? DiagnosticResult.Reason.ABSENT
							: DiagnosticResult.Reason.ERROR));
		}
		// @formatter:off
		then(result.getNotAvailable())
			.as("File store errors reported per mount")
			.containsAll(expected)
			;
		then(result.getData())
			.as("No mounts")
			.doesNotContainKey("mounts")
			.containsKey("db")
			;
		// @formatter:on
	}

	@Test
	public void run_truncated() throws Exception {
		// GIVEN
		createMounts();
		Files.createDirectories(tmpMount());
		expectDb("net.solarnetwork.node.dao.jdbc.h2");
		for ( int i = 0; i < 25; i++ ) {
			file(paths.getDbBackupDir().resolve(String.format("backup-%02d.zip", i)), 1, T0 + i);
		}

		StorageOp op = new StorageOp(bundleContext, paths, p -> new TestFileStore());

		// WHEN
		replayAll();
		DiagnosticResult result = op.run(context(Deadline.ofMillis(60_000L)));

		// THEN
		// @formatter:off
		then(result.getTruncated())
			.as("DB backups truncated")
			.containsExactly(Map.of("path", "data.dbBackups", "total", 25, "kept", 20,
					"reason", "maxItems"))
			;
		then(result.getData())
			.extractingByKey("dbBackups", LIST)
			.as("Newest backups kept")
			.hasSize(20)
			.first()
			.isEqualTo(info("backup-24.zip", 1L, T0 + 24))
			;
		// @formatter:on
	}

	@Test
	public void run_deadlineExpired() throws Exception {
		// GIVEN
		createFixture();
		StorageOp op = new StorageOp(bundleContext, paths, p -> new TestFileStore());

		// WHEN
		replayAll();
		DiagnosticResult result = op.run(context(new Deadline(0L, () -> 0L)));

		// THEN
		// @formatter:off
		then(result.getNotAvailable())
			.as("Timeout reported")
			.containsExactly(notAvailable("storage", DiagnosticResult.Reason.TIMEOUT))
			;
		then(result.getData()).as("No data collected").isEmpty();
		// @formatter:on
	}

	@Test
	public void run_deadlinePartial() throws Exception {
		// GIVEN
		createFixture();
		final long[] now = new long[] { 0L };
		// expire after the first mount check
		Deadline deadline = new Deadline(1L, () -> now[0]++ < 2 ? 0L : Long.MAX_VALUE / 2);
		StorageOp op = new StorageOp(bundleContext, paths, p -> new TestFileStore());

		// WHEN
		replayAll();
		DiagnosticResult result = op.run(context(deadline));

		// THEN
		// @formatter:off
		then(result.getNotAvailable())
			.as("Timeout reported")
			.containsExactly(notAvailable("storage", DiagnosticResult.Reason.TIMEOUT))
			;
		then(result.getData())
			.as("Partial mounts returned")
			.containsOnlyKeys("mounts")
			.extractingByKey("mounts", LIST)
			.hasSize(1)
			;
		// @formatter:on
	}

}
