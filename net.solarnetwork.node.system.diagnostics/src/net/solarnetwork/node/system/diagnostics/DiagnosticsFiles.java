/* ==================================================================
 * DiagnosticsFiles.java - 16/10/2026 9:00:00 am
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Bounded, read-only file system helpers.
 *
 * <p>
 * None of these helpers follow symbolic links for the files they inspect, and
 * all of them bound the amount of data they read or the number of entries they
 * visit.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public final class DiagnosticsFiles {

	/** The default maximum number of bytes read by {@link #readSmall(Path)}. */
	public static final int DEFAULT_MAX_BYTES = 64 * 1024;

	/**
	 * The maximum number of directory entries visited by
	 * {@link #list(Path, Pattern, int)}.
	 */
	public static final int MAX_LIST_SCAN_ENTRIES = 5000;

	/**
	 * Basic file information.
	 *
	 * @param name
	 *        the file name (without any directory)
	 * @param sizeBytes
	 *        the file size, in bytes
	 * @param mtimeMs
	 *        the file modification date, as an epoch millisecond value
	 * @param directory
	 *        {@code true} if the file is a directory
	 */
	public static record FileStat(String name, long sizeBytes, long mtimeMs, boolean directory) {

		/**
		 * Get a map of the file information.
		 *
		 * @return a map with {@code name}, {@code sizeBytes}, and
		 *         {@code mtimeMs} keys
		 */
		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>(4);
			m.put("name", name);
			m.put("sizeBytes", sizeBytes);
			m.put("mtimeMs", mtimeMs);
			return m;
		}

	}

	private DiagnosticsFiles() {
		// not available
	}

	/**
	 * Read a small regular file as UTF-8 text, using
	 * {@link #DEFAULT_MAX_BYTES}.
	 *
	 * @param path
	 *        the file to read
	 * @return the file content, or {@code null} if the file is not available
	 * @see #readSmall(Path, int)
	 */
	public static @Nullable String readSmall(Path path) {
		return readSmall(path, DEFAULT_MAX_BYTES);
	}

	/**
	 * Read a small regular file as UTF-8 text.
	 *
	 * <p>
	 * Symbolic links are not followed. Malformed UTF-8 is replaced with the
	 * Unicode replacement character. At most {@code maxBytes} bytes are read;
	 * any remaining content is ignored.
	 * </p>
	 *
	 * @param path
	 *        the file to read
	 * @param maxBytes
	 *        the maximum number of bytes to read
	 * @return the file content, or {@code null} if the file does not exist, is
	 *         not a regular file, or cannot be read
	 */
	public static @Nullable String readSmall(Path path, int maxBytes) {
		try {
			BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
			if ( !attrs.isRegularFile() ) {
				return null;
			}
			try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ,
					LinkOption.NOFOLLOW_LINKS)) {
				byte[] data = in.readNBytes(Math.max(0, maxBytes));
				return new String(data, StandardCharsets.UTF_8);
			}
		} catch ( IOException | SecurityException e ) {
			return null;
		}
	}

	/**
	 * Get basic information about a file, without following symbolic links.
	 *
	 * @param path
	 *        the file
	 * @return the information, or {@code null} if the file does not exist or
	 *         cannot be inspected
	 */
	public static @Nullable FileStat stat(Path path) {
		try {
			BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
			Path fileName = path.getFileName();
			return new FileStat(fileName != null ? fileName.toString() : path.toString(), attrs.size(),
					attrs.lastModifiedTime().toMillis(), attrs.isDirectory());
		} catch ( IOException | SecurityException e ) {
			return null;
		}
	}

	/**
	 * Get basic information about a file, without following symbolic links.
	 *
	 * @param path
	 *        the file
	 * @return a map with {@code name}, {@code sizeBytes}, and {@code mtimeMs}
	 *         keys, or {@code null} if the file does not exist or cannot be
	 *         inspected
	 */
	public static @Nullable Map<String, Object> fileInfo(Path path) {
		FileStat s = stat(path);
		return (s != null ? s.toMap() : null);
	}

	/**
	 * List the regular files and directories in a directory whose names match a
	 * pattern.
	 *
	 * <p>
	 * Symbolic links (and other non-regular files) are skipped. At most
	 * {@link #MAX_LIST_SCAN_ENTRIES} directory entries are visited.
	 * </p>
	 *
	 * @param dir
	 *        the directory to list
	 * @param namePattern
	 *        the pattern file names must fully match
	 * @param cap
	 *        the maximum number of paths to return
	 * @return the matching paths, sorted by name and limited to {@code cap}
	 *         elements; empty if the directory does not exist or cannot be read
	 */
	public static List<Path> list(Path dir, Pattern namePattern, int cap) {
		if ( cap < 1 || !Files.isDirectory(dir) ) {
			return Collections.emptyList();
		}
		List<Path> result = new ArrayList<>(Math.min(cap, 64));
		int visited = 0;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for ( Path p : stream ) {
				if ( ++visited > MAX_LIST_SCAN_ENTRIES ) {
					break;
				}
				Path fileName = p.getFileName();
				if ( fileName == null || !namePattern.matcher(fileName.toString()).matches() ) {
					continue;
				}
				if ( Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
						|| Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ) {
					result.add(p);
				}
			}
		} catch ( IOException | SecurityException e ) {
			// treat as unavailable; return what we found so far
		}
		result.sort(
				(l, r) -> String.valueOf(l.getFileName()).compareTo(String.valueOf(r.getFileName())));
		return (result.size() > cap ? new ArrayList<>(result.subList(0, cap)) : result);
	}

	/**
	 * Calculate the total size of the regular files in a directory tree, with
	 * bounds.
	 *
	 * <p>
	 * Symbolic links are not followed. Once {@code maxEntries} entries have
	 * been visited the walk stops, and the size calculated so far is returned.
	 * </p>
	 *
	 * @param dir
	 *        the directory to walk
	 * @param maxDepth
	 *        the maximum depth to walk, where {@code 1} means only the entries
	 *        directly within {@code dir}
	 * @param maxEntries
	 *        the maximum number of entries to visit
	 * @param deadline
	 *        the deadline
	 * @return the total size of all regular files visited, in bytes
	 * @throws IOException
	 *         if {@code dir} cannot be walked
	 * @throws DeadlineExceededException
	 *         if the deadline expires during the walk
	 */
	public static long dirSize(Path dir, int maxDepth, int maxEntries, Deadline deadline)
			throws IOException {
		final long[] total = new long[1];
		final int[] count = new int[1];
		try {
			Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), Math.max(0, maxDepth),
					new SimpleFileVisitor<Path>() {

						@Override
						public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs)
								throws IOException {
							deadline.check();
							return FileVisitResult.CONTINUE;
						}

						@Override
						public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
								throws IOException {
							deadline.check();
							if ( attrs.isRegularFile() ) {
								total[0] += attrs.size();
							}
							return (++count[0] >= maxEntries ? FileVisitResult.TERMINATE
									: FileVisitResult.CONTINUE);
						}

						@Override
						public FileVisitResult visitFileFailed(Path file, IOException exc)
								throws IOException {
							return FileVisitResult.CONTINUE;
						}

					});
		} catch ( NoSuchFileException e ) {
			return 0L;
		}
		return total[0];
	}

}
