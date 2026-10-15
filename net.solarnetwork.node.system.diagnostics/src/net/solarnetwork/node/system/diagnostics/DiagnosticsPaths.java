/* ==================================================================
 * DiagnosticsPaths.java - 16/10/2026 9:00:00 am
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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * File system locations used by the diagnostic operations.
 *
 * <p>
 * The defaults follow the SolarNodeOS {@code solarnode.service} environment
 * ({@code SOLARNODE_HOME}, {@code SOLARNODE_RAM_DIR}, {@code SOLARNODE_DB_DIR},
 * {@code SOLARNODE_LOGDIR}, {@code SOLARNODE_PERSIST_LOGDIR}).
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public final class DiagnosticsPaths {

	/** The default SolarNode home directory. */
	public static final String DEFAULT_HOME = "/var/lib/solarnode";

	/** The default SolarNode RAM directory. */
	public static final String DEFAULT_RAM_DIR = "/run/solarnode";

	private final Path procDir;
	private final List<Path> osReleaseFiles;
	private final Path homeDir;
	private final Path ramDir;
	private final List<Path> dbDirs;
	private final Path dbBackupDir;
	private final Path settingsBackupDir;
	private final Path logDir;
	private final Path persistLogDir;
	private final List<Path> mountPoints;

	private DiagnosticsPaths(Path procDir, List<Path> osReleaseFiles, Path homeDir, Path ramDir,
			List<Path> dbDirs, Path dbBackupDir, Path settingsBackupDir, Path logDir, Path persistLogDir,
			List<Path> mountPoints) {
		super();
		this.procDir = procDir;
		this.osReleaseFiles = List.copyOf(osReleaseFiles);
		this.homeDir = homeDir;
		this.ramDir = ramDir;
		this.dbDirs = List.copyOf(dbDirs);
		this.dbBackupDir = dbBackupDir;
		this.settingsBackupDir = settingsBackupDir;
		this.logDir = logDir;
		this.persistLogDir = persistLogDir;
		this.mountPoints = List.copyOf(mountPoints);
	}

	/**
	 * Create an instance from the process environment.
	 *
	 * @return the new instance
	 */
	public static DiagnosticsPaths defaults() {
		return fromEnvironment(System::getenv, System.getProperty("sn.home"));
	}

	/**
	 * Create an instance from an environment lookup function.
	 *
	 * @param env
	 *        the environment variable lookup function
	 * @param snHome
	 *        an optional {@code sn.home} system property value, used if the
	 *        {@code SOLARNODE_HOME} environment variable is not available
	 * @return the new instance
	 */
	public static DiagnosticsPaths fromEnvironment(Function<String, @Nullable String> env,
			@Nullable String snHome) {
		String homeVal = nonEmpty(env.apply("SOLARNODE_HOME"));
		if ( homeVal == null ) {
			homeVal = nonEmpty(snHome);
		}
		final Path home = Paths.get(homeVal != null ? homeVal : DEFAULT_HOME);
		final String ramVal = nonEmpty(env.apply("SOLARNODE_RAM_DIR"));
		final Path ram = Paths.get(ramVal != null ? ramVal : DEFAULT_RAM_DIR);

		Set<Path> dbDirs = new LinkedHashSet<>(4);
		String dbVal = nonEmpty(env.apply("SOLARNODE_DB_DIR"));
		if ( dbVal != null ) {
			dbDirs.add(Paths.get(dbVal));
		}
		dbDirs.add(ram.resolve("db"));
		dbDirs.add(home.resolve("var/db"));

		final String logVal = nonEmpty(env.apply("SOLARNODE_LOGDIR"));
		final Path logDir = (logVal != null ? Paths.get(logVal) : ram.resolve("log"));
		final String persistLogVal = nonEmpty(env.apply("SOLARNODE_PERSIST_LOGDIR"));
		final Path persistLogDir = (persistLogVal != null ? Paths.get(persistLogVal)
				: home.resolve("var/log"));

		Set<Path> mounts = new LinkedHashSet<>(6);
		mounts.add(Paths.get("/"));
		mounts.add(Paths.get("/run"));
		mounts.add(ram);
		mounts.add(home);
		mounts.add(Paths.get("/tmp"));

		return new DiagnosticsPaths(Paths.get("/proc"),
				List.of(Paths.get("/etc/os-release"), Paths.get("/usr/lib/os-release")), home, ram,
				new ArrayList<>(dbDirs), home.resolve("var/db-bak"), home.resolve("var/settings-bak"),
				logDir, persistLogDir, new ArrayList<>(mounts));
	}

	/**
	 * Create an instance with all default (absolute) locations re-rooted under
	 * a given directory.
	 *
	 * <p>
	 * This is designed for testing. The process environment is ignored.
	 * </p>
	 *
	 * @param root
	 *        the root directory
	 * @return the new instance
	 */
	public static DiagnosticsPaths rootedAt(Path root) {
		DiagnosticsPaths d = fromEnvironment(k -> null, null);
		UnaryOperator<Path> r = p -> root.resolve(p.toString().replaceFirst("^/+", ""));
		return new DiagnosticsPaths(r.apply(d.procDir), d.osReleaseFiles.stream().map(r).toList(),
				r.apply(d.homeDir), r.apply(d.ramDir), d.dbDirs.stream().map(r).toList(),
				r.apply(d.dbBackupDir), r.apply(d.settingsBackupDir), r.apply(d.logDir),
				r.apply(d.persistLogDir), d.mountPoints.stream().map(r).toList());
	}

	private static @Nullable String nonEmpty(@Nullable String s) {
		return (s != null && !s.isBlank() ? s.trim() : null);
	}

	/**
	 * Get the {@code /proc} directory.
	 *
	 * @return the directory
	 */
	public Path getProcDir() {
		return procDir;
	}

	/**
	 * Get the primary {@code os-release} file.
	 *
	 * @return the file, {@code /etc/os-release} by default
	 * @see #getOsReleaseFiles()
	 */
	public Path getOsReleaseFile() {
		return osReleaseFiles.get(0);
	}

	/**
	 * Get the {@code os-release} file candidates, in order of preference.
	 *
	 * <p>
	 * Following {@code os-release(5)}, these are {@code /etc/os-release} and
	 * then {@code /usr/lib/os-release}. The former is commonly a symbolic link
	 * to the latter, and symbolic links are not followed when reading files, so
	 * the first candidate that is a readable regular file should be used.
	 * </p>
	 *
	 * @return the files, never {@code null} or empty
	 */
	public List<Path> getOsReleaseFiles() {
		return osReleaseFiles;
	}

	/**
	 * Get the SolarNode home directory.
	 *
	 * @return the directory
	 */
	public Path getHomeDir() {
		return homeDir;
	}

	/**
	 * Get the SolarNode RAM directory.
	 *
	 * @return the directory
	 */
	public Path getRamDir() {
		return ramDir;
	}

	/**
	 * Get the candidate database directories.
	 *
	 * @return the directories, never {@code null}
	 */
	public List<Path> getDbDirs() {
		return dbDirs;
	}

	/**
	 * Get the database backup directory.
	 *
	 * @return the directory
	 */
	public Path getDbBackupDir() {
		return dbBackupDir;
	}

	/**
	 * Get the settings backup directory.
	 *
	 * @return the directory
	 */
	public Path getSettingsBackupDir() {
		return settingsBackupDir;
	}

	/**
	 * Get the SolarNode log directory.
	 *
	 * @return the directory
	 */
	public Path getLogDir() {
		return logDir;
	}

	/**
	 * Get the persistent log directory.
	 *
	 * @return the directory
	 */
	public Path getPersistLogDir() {
		return persistLogDir;
	}

	/**
	 * Get the mount points to report storage for.
	 *
	 * @return the mount points, never {@code null}
	 */
	public List<Path> getMountPoints() {
		return mountPoints;
	}

}
