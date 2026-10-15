/* ==================================================================
 * LogFiles.java - 16/10/2026 9:00:00 am
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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A snapshot of the SolarNode log files in a directory.
 *
 * <p>
 * Only regular files (not following links) directly within the log directory
 * and named like {@code solarnode.log} or {@code solarnode-N.log} (where
 * {@code N} is 1 - 9) are included. The file key, size, and modification time
 * of each file is captured when the snapshot is taken. Files are ordered newest
 * first. Files sharing the same file key (for example hard links) are included
 * only once.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public final class LogFiles {

	/** The log file name pattern. */
	public static final Pattern LOG_FILE_NAME = Pattern.compile("solarnode(?:-([1-9]))?+\\.log");

	/** The maximum number of directory entries to inspect. */
	public static final int MAX_DIR_ENTRIES = 1000;

	private final Path dir;
	private final List<LogFile> files;
	private final LogLayout layout;

	/**
	 * A snapshot of a single log file.
	 */
	public static final class LogFile {

		private final Path path;
		private final int index;
		private final @Nullable Object fileKey;
		private final long size;
		private final long modifiedMs;

		/**
		 * Constructor.
		 *
		 * @param path
		 *        the path
		 * @param index
		 *        the rotation index, {@code 0} for {@code solarnode.log}
		 * @param fileKey
		 *        the file key, or {@code null} if not supported
		 * @param size
		 *        the size, in bytes
		 * @param modifiedMs
		 *        the modification time, as an epoch millisecond value
		 */
		public LogFile(Path path, int index, @Nullable Object fileKey, long size, long modifiedMs) {
			super();
			this.path = path;
			this.index = index;
			this.fileKey = fileKey;
			this.size = size;
			this.modifiedMs = modifiedMs;
		}

		/**
		 * Get the path.
		 *
		 * @return the path
		 */
		public Path getPath() {
			return path;
		}

		/**
		 * Get the file name.
		 *
		 * @return the file name
		 */
		public String getName() {
			Path n = path.getFileName();
			return (n != null ? n.toString() : "");
		}

		/**
		 * Get the rotation index.
		 *
		 * @return the index, {@code 0} for {@code solarnode.log}
		 */
		public int getIndex() {
			return index;
		}

		/**
		 * Get the file key.
		 *
		 * @return the file key, or {@code null} if not supported
		 */
		public @Nullable Object getFileKey() {
			return fileKey;
		}

		/**
		 * Get the size at the time of the snapshot.
		 *
		 * @return the size, in bytes
		 */
		public long getSize() {
			return size;
		}

		/**
		 * Get the modification time at the time of the snapshot.
		 *
		 * @return the modification time, as an epoch millisecond value
		 */
		public long getModifiedMs() {
			return modifiedMs;
		}

		/**
		 * Get information about this file as a map.
		 *
		 * @return a map with {@code name}, {@code sizeBytes}, and
		 *         {@code mtimeMs} keys
		 */
		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>(3);
			m.put("name", getName());
			m.put("sizeBytes", size);
			m.put("mtimeMs", modifiedMs);
			return m;
		}

	}

	private LogFiles(Path dir, List<LogFile> files, LogLayout layout) {
		super();
		this.dir = dir;
		this.files = files;
		this.layout = layout;
	}

	/**
	 * Take a snapshot of the log files in a directory.
	 *
	 * <p>
	 * The layout is detected from the first non-blank line of the newest
	 * non-empty file. If there are no non-empty files,
	 * {@link LogLayout#DEFAULT} is assumed.
	 * </p>
	 *
	 * @param dir
	 *        the log directory
	 * @return the snapshot, or {@code null} if {@code dir} is not a directory
	 * @throws IOException
	 *         if an IO error occurs
	 */
	public static @Nullable LogFiles snapshot(Path dir) throws IOException {
		if ( !Files.isDirectory(dir) ) {
			return null;
		}
		final List<LogFile> list = list(dir);
		LogLayout layout = null;
		for ( LogFile f : list ) {
			if ( f.getSize() < 1 ) {
				continue;
			}
			String line = firstLine(f.getPath());
			if ( line != null ) {
				layout = LogLayout.detect(line);
				break;
			}
		}
		return new LogFiles(dir, list, layout != null ? layout : LogLayout.DEFAULT);
	}

	private static List<LogFile> list(Path dir) throws IOException {
		final List<LogFile> list = new ArrayList<>(4);
		final Set<Object> keys = new HashSet<>(4);
		int count = 0;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
			for ( Path p : ds ) {
				if ( ++count > MAX_DIR_ENTRIES ) {
					break;
				}
				Path name = p.getFileName();
				if ( name == null ) {
					continue;
				}
				Matcher m = LOG_FILE_NAME.matcher(name.toString());
				if ( !m.matches() ) {
					continue;
				}
				final BasicFileAttributes attrs;
				try {
					attrs = Files.readAttributes(p, BasicFileAttributes.class,
							LinkOption.NOFOLLOW_LINKS);
				} catch ( IOException e ) {
					// removed or not accessible
					continue;
				}
				if ( !attrs.isRegularFile() ) {
					continue;
				}
				final Object key = attrs.fileKey();
				if ( key != null && !keys.add(key) ) {
					continue;
				}
				final String idx = m.group(1);
				list.add(new LogFile(p, idx != null ? Integer.parseInt(idx) : 0, key, attrs.size(),
						attrs.lastModifiedTime().toMillis()));
			}
		}
		list.sort(Comparator.comparingLong(LogFile::getModifiedMs).reversed()
				.thenComparingInt(LogFile::getIndex));
		return list;
	}

	/**
	 * Read the first non-blank line of a file.
	 *
	 * @param path
	 *        the file
	 * @return the line, or {@code null} if not available
	 * @throws IOException
	 *         if an IO error occurs
	 * @see LogScanner#firstLine(java.nio.channels.ReadableByteChannel)
	 */
	public static @Nullable String firstLine(Path path) throws IOException {
		try (FileChannel ch = open(path)) {
			return LogScanner.firstLine(ch);
		}
	}

	/**
	 * Open a file for reading, without following links.
	 *
	 * @param path
	 *        the path to open
	 * @return the channel
	 * @throws IOException
	 *         if an IO error occurs
	 */
	public static FileChannel open(Path path) throws IOException {
		return FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * Locate the current path of a snapshot file, handling log rotation.
	 *
	 * <p>
	 * If the file at the snapshot path no longer has the snapshot file key, the
	 * directory is listed again to find the file with that key.
	 * </p>
	 *
	 * @param file
	 *        the snapshot file
	 * @return the current path of the file, or {@code null} if the file can no
	 *         longer be found
	 * @throws IOException
	 *         if an IO error occurs
	 */
	public @Nullable Path locate(LogFile file) throws IOException {
		final Object key = file.getFileKey();
		final Path path = file.getPath();
		if ( key == null ) {
			return (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? path : null);
		}
		if ( key.equals(fileKey(path)) ) {
			return path;
		}
		for ( LogFile f : list(dir) ) {
			if ( key.equals(f.getFileKey()) ) {
				return f.getPath();
			}
		}
		return null;
	}

	/**
	 * Get the file key of a path.
	 *
	 * @param path
	 *        the path
	 * @return the file key, or {@code null} if not available
	 */
	public static @Nullable Object fileKey(Path path) {
		try {
			return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
					.fileKey();
		} catch ( IOException e ) {
			return null;
		}
	}

	/**
	 * Get the earliest available log timestamp.
	 *
	 * <p>
	 * This is taken from the first line of the oldest file.
	 * </p>
	 *
	 * @param zone
	 *        the time zone of the log timestamps
	 * @return the earliest timestamp, as an epoch millisecond value, or
	 *         {@code null} if not available
	 */
	public @Nullable Long earliestMs(ZoneId zone) {
		if ( layout == LogLayout.UNSUPPORTED ) {
			return null;
		}
		for ( int i = files.size() - 1; i >= 0; i-- ) {
			LogFile f = files.get(i);
			if ( f.getSize() < 1 ) {
				continue;
			}
			try {
				String line = firstLine(f.getPath());
				LogEntry e = (line != null ? layout.parseHeader(line, zone) : null);
				if ( e != null ) {
					return e.getTimestampMs();
				}
			} catch ( IOException e ) {
				// ignore and try next
			}
		}
		return null;
	}

	/**
	 * Get the files whose modification time is not before a given time.
	 *
	 * @param sinceMs
	 *        the minimum modification time, as an epoch millisecond value
	 * @return the files, newest first
	 */
	public List<LogFile> filesSince(long sinceMs) {
		return files.stream().filter(f -> f.getModifiedMs() >= sinceMs).toList();
	}

	/**
	 * Get the log directory.
	 *
	 * @return the directory
	 */
	public Path getDir() {
		return dir;
	}

	/**
	 * Get the files.
	 *
	 * @return the files, newest first
	 */
	public List<LogFile> getFiles() {
		return files;
	}

	/**
	 * Get the detected layout.
	 *
	 * @return the layout, never {@code null}
	 */
	public LogLayout getLayout() {
		return layout;
	}

}
