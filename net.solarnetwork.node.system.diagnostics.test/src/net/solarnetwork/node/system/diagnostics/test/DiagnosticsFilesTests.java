/* ==================================================================
 * DiagnosticsFilesTests.java - 16/10/2026 9:00:00 am
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import net.solarnetwork.node.system.diagnostics.Deadline;
import net.solarnetwork.node.system.diagnostics.DeadlineExceededException;
import net.solarnetwork.node.system.diagnostics.DiagnosticsFiles;

/**
 * Test cases for the {@link DiagnosticsFiles} class.
 *
 * @author elijah
 * @version 1.0
 */
public class DiagnosticsFilesTests {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private Path dir;

	@Before
	public void setup() {
		dir = tmp.getRoot().toPath();
	}

	@Test
	public void readSmall() throws IOException {
		// GIVEN
		Path f = Files.writeString(dir.resolve("a.txt"), "Hello, world.\n");

		// WHEN
		String result = DiagnosticsFiles.readSmall(f);

		// THEN
		then(result).as("File content returned").isEqualTo("Hello, world.\n");
	}

	@Test
	public void readSmall_capped() throws IOException {
		// GIVEN
		Path f = Files.writeString(dir.resolve("a.txt"), "0123456789");

		// WHEN
		String result = DiagnosticsFiles.readSmall(f, 4);

		// THEN
		then(result).as("Only max bytes read").isEqualTo("0123");
	}

	@Test
	public void readSmall_malformedUtf8() throws IOException {
		// GIVEN
		Path f = Files.write(dir.resolve("a.txt"), new byte[] { 'a', (byte) 0xC3, (byte) 0x28, 'b' });

		// WHEN
		String result = DiagnosticsFiles.readSmall(f);

		// THEN
		then(result).as("Malformed UTF-8 replaced").isEqualTo("a�(b");
	}

	@Test
	public void readSmall_absent() {
		// WHEN
		String result = DiagnosticsFiles.readSmall(dir.resolve("nope"));

		// THEN
		then(result).as("Null for missing file").isNull();
	}

	@Test
	public void readSmall_directory() throws IOException {
		// GIVEN
		Path d = Files.createDirectory(dir.resolve("d"));

		// WHEN
		String result = DiagnosticsFiles.readSmall(d);

		// THEN
		then(result).as("Null for directory").isNull();
	}

	@Test
	public void readSmall_symlink() throws IOException {
		// GIVEN
		Path f = Files.writeString(dir.resolve("a.txt"), "secret");
		Path link = Files.createSymbolicLink(dir.resolve("link.txt"), f);

		// WHEN
		String result = DiagnosticsFiles.readSmall(link);

		// THEN
		then(result).as("Symbolic link not followed").isNull();
	}

	@Test
	public void fileInfo() throws IOException {
		// GIVEN
		Path f = Files.writeString(dir.resolve("a.txt"), "12345");
		Files.setLastModifiedTime(f, FileTime.fromMillis(1760000000000L));

		// WHEN
		Map<String, Object> result = DiagnosticsFiles.fileInfo(f);

		// THEN
		// @formatter:off
		then(result)
			.as("File info returned")
			.containsOnlyKeys("name", "sizeBytes", "mtimeMs")
			.containsEntry("name", "a.txt")
			.containsEntry("sizeBytes", 5L)
			.containsEntry("mtimeMs", 1760000000000L)
			;
		// @formatter:on
	}

	@Test
	public void fileInfo_absent() {
		// WHEN
		Map<String, Object> result = DiagnosticsFiles.fileInfo(dir.resolve("nope"));

		// THEN
		then(result).as("Null for missing file").isNull();
	}

	@Test
	public void list() throws IOException {
		// GIVEN
		Files.writeString(dir.resolve("c.log"), "c");
		Files.writeString(dir.resolve("a.log"), "a");
		Files.writeString(dir.resolve("b.txt"), "b");
		Files.createDirectory(dir.resolve("d.log"));
		Files.createSymbolicLink(dir.resolve("e.log"), dir.resolve("a.log"));

		// WHEN
		List<Path> result = DiagnosticsFiles.list(dir, Pattern.compile("^.+\\.log$"), 10);

		// THEN
		// @formatter:off
		then(result)
			.as("Matching regular files and directories returned sorted by name, links ignored")
			.containsExactly(dir.resolve("a.log"), dir.resolve("c.log"), dir.resolve("d.log"))
			;
		// @formatter:on
	}

	@Test
	public void list_capped() throws IOException {
		// GIVEN
		for ( int i = 0; i < 5; i++ ) {
			Files.writeString(dir.resolve("f" + i + ".log"), "x");
		}

		// WHEN
		List<Path> result = DiagnosticsFiles.list(dir, Pattern.compile("^.+\\.log$"), 2);

		// THEN
		// @formatter:off
		then(result)
			.as("Result capped after sorting")
			.containsExactly(dir.resolve("f0.log"), dir.resolve("f1.log"))
			;
		// @formatter:on
	}

	@Test
	public void list_absent() {
		// WHEN
		List<Path> result = DiagnosticsFiles.list(dir.resolve("nope"), Pattern.compile(".*"), 10);

		// THEN
		then(result).as("Empty for missing directory").isEmpty();
	}

	@Test
	public void dirSize() throws IOException {
		// GIVEN
		Files.write(dir.resolve("a"), new byte[10]);
		Files.write(dir.resolve("b"), new byte[20]);
		Path sub = Files.createDirectory(dir.resolve("sub"));
		Files.write(sub.resolve("c"), new byte[100]);
		Path other = tmp.newFolder("other").toPath();
		Files.write(other.resolve("big"), new byte[1000]);
		Files.createSymbolicLink(dir.resolve("link"), other);

		// WHEN
		long result = DiagnosticsFiles.dirSize(dir, 1, 100, Deadline.ofMillis(60_000L));

		// THEN
		then(result).as("Only top-level regular files counted, links not followed").isEqualTo(30L);
	}

	@Test
	public void dirSize_maxEntries() throws IOException {
		// GIVEN
		for ( int i = 0; i < 10; i++ ) {
			Files.write(dir.resolve("f" + i), new byte[10]);
		}

		// WHEN
		long result = DiagnosticsFiles.dirSize(dir, 1, 3, Deadline.ofMillis(60_000L));

		// THEN
		then(result).as("Walk stopped after max entries").isEqualTo(30L);
	}

	@Test
	public void dirSize_deadline() throws IOException {
		// GIVEN
		Files.write(dir.resolve("a"), "a".getBytes(UTF_8));
		Deadline deadline = new Deadline(0L, () -> 0L);

		// THEN
		// @formatter:off
		thenThrownBy(() -> DiagnosticsFiles.dirSize(dir, 1, 100, deadline))
			.as("Expired deadline throws")
			.isInstanceOf(DeadlineExceededException.class)
			;
		// @formatter:on
	}

}
