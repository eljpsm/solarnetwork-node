/* ==================================================================
 * StorageOp.java - 16/10/2026 9:00:00 am
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

import static net.solarnetwork.util.ObjectUtils.requireNonNullArgument;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.solarnetwork.node.system.diagnostics.DiagnosticsFiles.FileStat;

/**
 * Diagnostic operation to report storage information.
 *
 * <p>
 * This operation only inspects file system metadata: it never reads file
 * contents. It reports file system usage for a fixed set of mount points, the
 * node database files and reset backups, database backup archives, settings
 * backups, log files, and settings audit log files.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public class StorageOp implements DiagnosticOp {

	/** The operation name. */
	public static final String NAME = "storage";

	/** The pattern for database file names. */
	public static final Pattern DB_FILE_PATTERN = Pattern
			.compile("^[A-Za-z0-9_.-]+\\.(mv\\.db|trace\\.db|h2\\.db|lock\\.db|tempFile)$");

	/** The pattern for database reset backup directory names. */
	public static final Pattern DB_RESET_DIR_PATTERN = Pattern.compile("^.+-reset\\.([0-9]{13})$");

	/** The pattern for database backup file names. */
	public static final Pattern DB_BACKUP_PATTERN = Pattern.compile("^.+\\.zip$");

	/** The pattern for settings backup file names. */
	public static final Pattern SETTINGS_BACKUP_PATTERN = Pattern.compile("^settings_.*\\.txt$");

	/** The pattern for log file names. */
	public static final Pattern LOG_FILE_PATTERN = Pattern.compile("^solarnode.*\\.log(\\.gz)?$");

	/** The pattern for settings audit log file names. */
	public static final Pattern AUDIT_LOG_PATTERN = Pattern.compile("^solarnode-settings-audit.*$");

	/** The maximum number of database files to list per directory. */
	public static final int MAX_DB_FILES = 50;

	/** The maximum number of reset backups to list per directory. */
	public static final int MAX_RESET_BACKUPS = 10;

	/** The maximum number of database backups to list. */
	public static final int MAX_DB_BACKUPS = 20;

	/** The maximum number of settings backups to list. */
	public static final int MAX_SETTINGS_BACKUPS = 10;

	/** The maximum number of log files to list. */
	public static final int MAX_LOG_FILES = 20;

	/** The maximum number of entries to visit when sizing a reset backup. */
	public static final int MAX_RESET_BACKUP_ENTRIES = 200;

	/** The maximum number of matching files to consider in any directory. */
	public static final int MAX_SCAN_FILES = 1000;

	/** The truncation reason used when a list is capped. */
	public static final String TRUNCATED_REASON = "maxItems";

	/**
	 * API for looking up a {@link FileStore} for a path.
	 */
	@FunctionalInterface
	public interface FileStoreLookup {

		/**
		 * Get the file store for a path.
		 *
		 * @param path
		 *        the path
		 * @return the file store, never {@code null}
		 * @throws IOException
		 *         if an I/O error occurs
		 */
		FileStore get(Path path) throws IOException;

	}

	private static final Logger log = LoggerFactory.getLogger(StorageOp.class);

	private static final Comparator<FileStat> NEWEST_FIRST = Comparator.comparingLong(FileStat::mtimeMs)
			.reversed().thenComparing(FileStat::name);

	private static final Comparator<FileStat> BY_NAME = Comparator.comparing(FileStat::name);

	private final BundleContext bundleContext;
	private final DiagnosticsPaths paths;
	private final FileStoreLookup fileStoreLookup;

	/**
	 * Constructor.
	 *
	 * @param bundleContext
	 *        the bundle context
	 * @param paths
	 *        the paths
	 * @throws IllegalArgumentException
	 *         if any argument is {@code null}
	 */
	public StorageOp(BundleContext bundleContext, DiagnosticsPaths paths) {
		this(bundleContext, paths, Files::getFileStore);
	}

	/**
	 * Constructor.
	 *
	 * @param bundleContext
	 *        the bundle context
	 * @param paths
	 *        the paths
	 * @param fileStoreLookup
	 *        the file store lookup function
	 * @throws IllegalArgumentException
	 *         if any argument is {@code null}
	 */
	public StorageOp(BundleContext bundleContext, DiagnosticsPaths paths,
			FileStoreLookup fileStoreLookup) {
		super();
		this.bundleContext = requireNonNullArgument(bundleContext, "bundleContext");
		this.paths = requireNonNullArgument(paths, "paths");
		this.fileStoreLookup = requireNonNullArgument(fileStoreLookup, "fileStoreLookup");
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public int version() {
		return 1;
	}

	@Override
	public RateClass rateClass() {
		return RateClass.LIGHT;
	}

	/**
	 * A data item collector.
	 */
	@FunctionalInterface
	private interface ItemCollector {

		void collect() throws Exception;

	}

	@Override
	public DiagnosticResult run(OpContext ctx) throws Exception {
		final DiagnosticResult result = new DiagnosticResult();
		final Deadline deadline = ctx.getDeadline();
		final List<Map<String, Object>> mounts = new ArrayList<>(8);
		try {
			for ( Path mount : paths.getMountPoints() ) {
				item(deadline, result, "mounts:" + mount, () -> mount(result, mount, mounts));
			}
			if ( !mounts.isEmpty() ) {
				result.put("mounts", mounts);
			}
			item(deadline, result, "db", () -> db(result, deadline));
			item(deadline, result, "dbBackups", () -> dbBackups(result));
			item(deadline, result, "settingsBackups", () -> settingsBackups(result));
			item(deadline, result, "logs", () -> result.put("logs",
					fileGroup(result, "logs", paths.getLogDir(), LOG_FILE_PATTERN)));
			item(deadline, result, "auditLog", () -> result.put("auditLog",
					fileGroup(result, "auditLog", paths.getPersistLogDir(), AUDIT_LOG_PATTERN)));
		} catch ( DeadlineExceededException e ) {
			if ( !mounts.isEmpty() ) {
				result.put("mounts", mounts);
			}
			result.notAvailable(NAME, DiagnosticResult.Reason.TIMEOUT);
		}
		return result;
	}

	private static void item(Deadline deadline, DiagnosticResult result, String item,
			ItemCollector collector) {
		deadline.check();
		try {
			collector.collect();
		} catch ( DeadlineExceededException e ) {
			throw e;
		} catch ( Exception e ) {
			log.debug("Error collecting storage diagnostic item [{}]: {}", item, e.toString());
			result.notAvailable(item, DiagnosticResult.Reason.ERROR);
		}
	}

	private void mount(DiagnosticResult result, Path mount, List<Map<String, Object>> mounts)
			throws IOException {
		if ( !Files.exists(mount) ) {
			result.notAvailable("mounts:" + mount, DiagnosticResult.Reason.ABSENT);
			return;
		}
		final FileStore store = fileStoreLookup.get(mount);
		final Map<String, Object> m = new LinkedHashMap<>(4);
		m.put("path", mount.toString());
		m.put("type", store.type());
		m.put("totalBytes", store.getTotalSpace());
		m.put("usableBytes", store.getUsableSpace());
		mounts.add(m);
	}

	private void db(DiagnosticResult result, Deadline deadline) {
		final Map<String, Object> db = new LinkedHashMap<>(2);
		try {
			Object backend = OsgiRuntimeInfo.dbBackend(bundleContext).get("backend");
			db.put("backend", backend != null ? backend : OsgiRuntimeInfo.BACKEND_UNKNOWN);
		} catch ( RuntimeException e ) {
			log.debug("Error resolving DB backend: {}", e.toString());
			result.notAvailable("db/backend", DiagnosticResult.Reason.ERROR);
		}
		final List<Map<String, Object>> dirs = new ArrayList<>(2);
		db.put("dirs", dirs);
		try {
			for ( Path dir : paths.getDbDirs() ) {
				deadline.check();
				if ( !Files.isDirectory(dir) ) {
					continue;
				}
				dirs.add(dbDir(result, dir, dirs.size(), deadline));
			}
		} finally {
			result.put("db", db);
		}
	}

	private Map<String, Object> dbDir(DiagnosticResult result, Path dir, int index, Deadline deadline)
			throws DeadlineExceededException {
		final Map<String, Object> m = new LinkedHashMap<>(4);
		m.put("path", dir.toString());

		List<FileStat> files = stats(DiagnosticsFiles.list(dir, DB_FILE_PATTERN, MAX_SCAN_FILES), false);
		files.sort(BY_NAME);
		m.put("files", capped(result, "data.db.dirs[" + index + "].files", files, MAX_DB_FILES));

		final List<Map<String, Object>> resets = new ArrayList<>(2);
		final List<FileStat> resetDirs = stats(
				DiagnosticsFiles.list(dir, DB_RESET_DIR_PATTERN, MAX_SCAN_FILES), true);
		resetDirs.sort(Comparator.comparingLong(StorageOp::resetTakenMs).reversed()
				.thenComparing(FileStat::name));
		if ( resetDirs.size() > MAX_RESET_BACKUPS ) {
			result.truncated("data.db.dirs[" + index + "].resetBackups", resetDirs.size(),
					MAX_RESET_BACKUPS, TRUNCATED_REASON);
		}
		for ( int i = 0, len = Math.min(resetDirs.size(), MAX_RESET_BACKUPS); i < len; i++ ) {
			deadline.check();
			final FileStat reset = resetDirs.get(i);
			final Path resetDir = dir.resolve(reset.name());
			final Map<String, Object> r = new LinkedHashMap<>(4);
			r.put("name", reset.name());
			r.put("takenMs", resetTakenMs(reset));
			try {
				r.put("sizeBytes",
						DiagnosticsFiles.dirSize(resetDir, 1, MAX_RESET_BACKUP_ENTRIES, deadline));
			} catch ( IOException e ) {
				result.notAvailable("db/resetBackups:" + reset.name(), DiagnosticResult.Reason.ERROR);
			}
			List<FileStat> resetFiles = stats(
					DiagnosticsFiles.list(resetDir, DB_FILE_PATTERN, MAX_SCAN_FILES), false);
			resetFiles.sort(BY_NAME);
			r.put("files", capped(result, "data.db.dirs[" + index + "].resetBackups[" + i + "].files",
					resetFiles, MAX_DB_FILES));
			resets.add(r);
		}
		m.put("resetBackups", resets);
		return m;
	}

	private static long resetTakenMs(FileStat s) {
		Matcher m = DB_RESET_DIR_PATTERN.matcher(s.name());
		return (m.matches() ? Long.parseLong(m.group(1)) : 0L);
	}

	private void dbBackups(DiagnosticResult result) {
		final Path dir = paths.getDbBackupDir();
		if ( !Files.isDirectory(dir) ) {
			result.notAvailable("dbBackups", DiagnosticResult.Reason.ABSENT);
			return;
		}
		List<FileStat> files = stats(DiagnosticsFiles.list(dir, DB_BACKUP_PATTERN, MAX_SCAN_FILES),
				false);
		files.sort(NEWEST_FIRST);
		result.put("dbBackups", capped(result, "data.dbBackups", files, MAX_DB_BACKUPS));
	}

	private void settingsBackups(DiagnosticResult result) {
		final Path dir = paths.getSettingsBackupDir();
		if ( !Files.isDirectory(dir) ) {
			result.notAvailable("settingsBackups", DiagnosticResult.Reason.ABSENT);
			return;
		}
		List<FileStat> files = stats(DiagnosticsFiles.list(dir, SETTINGS_BACKUP_PATTERN, MAX_SCAN_FILES),
				false);
		files.sort(NEWEST_FIRST);
		final Map<String, Object> m = new LinkedHashMap<>(8);
		m.put("count", files.size());
		m.put("totalBytes", totalBytes(files));
		if ( !files.isEmpty() ) {
			m.put("newestMs", files.get(0).mtimeMs());
			m.put("oldestMs", files.get(files.size() - 1).mtimeMs());
		}
		m.put("files", toMaps(files, MAX_SETTINGS_BACKUPS));
		result.put("settingsBackups", m);
	}

	private Map<String, Object> fileGroup(DiagnosticResult result, String item, Path dir,
			Pattern pattern) {
		final Map<String, Object> m = new LinkedHashMap<>(4);
		m.put("dir", dir.toString());
		if ( !Files.isDirectory(dir) ) {
			result.notAvailable(item, DiagnosticResult.Reason.ABSENT);
			return m;
		}
		List<FileStat> files = stats(DiagnosticsFiles.list(dir, pattern, MAX_SCAN_FILES), false);
		files.sort(NEWEST_FIRST);
		m.put("totalBytes", totalBytes(files));
		m.put("files", capped(result, "data." + item + ".files", files, MAX_LOG_FILES));
		return m;
	}

	private static List<FileStat> stats(List<Path> list, boolean directories) {
		final List<FileStat> result = new ArrayList<>(list.size());
		for ( Path p : list ) {
			FileStat s = DiagnosticsFiles.stat(p);
			if ( s != null && s.directory() == directories ) {
				result.add(s);
			}
		}
		return result;
	}

	private static long totalBytes(List<FileStat> files) {
		long total = 0;
		for ( FileStat s : files ) {
			total += s.sizeBytes();
		}
		return total;
	}

	private static List<Map<String, Object>> capped(DiagnosticResult result, String path,
			List<FileStat> files, int cap) {
		if ( files.size() > cap ) {
			result.truncated(path, files.size(), cap, TRUNCATED_REASON);
		}
		return toMaps(files, cap);
	}

	private static List<Map<String, Object>> toMaps(List<FileStat> files, int cap) {
		final int len = Math.min(files.size(), cap);
		final List<Map<String, Object>> result = new ArrayList<>(len);
		for ( int i = 0; i < len; i++ ) {
			result.add(files.get(i).toMap());
		}
		return result;
	}

}
