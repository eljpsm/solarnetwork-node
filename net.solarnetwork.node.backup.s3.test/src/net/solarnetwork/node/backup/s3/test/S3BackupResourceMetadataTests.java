/* ==================================================================
 * S3BackupResourceMetadataTests.java - 10/07/2026 6:36:09 am
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

package net.solarnetwork.node.backup.s3.test;

import static net.solarnetwork.test.CommonTestUtils.randomString;
import static org.assertj.core.api.BDDAssertions.from;
import static org.assertj.core.api.BDDAssertions.then;
import java.io.IOException;
import org.junit.Test;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.solarnetwork.node.backup.s3.S3BackupResourceMetadata;

/**
 * Test cases for the {@link S3BackupResourceMetadata} class.
 *
 * @author matt
 * @version 1.0
 */
public class S3BackupResourceMetadataTests {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	@Test
	public void toJson() throws IOException {
		// GIVEN
		final String backupPath = randomString();
		final String providerKey = randomString();
		final String objectKey = randomString();
		final long ts = System.currentTimeMillis();
		final String digest = randomString();
		final S3BackupResourceMetadata meta = new S3BackupResourceMetadata(backupPath, ts, providerKey,
				objectKey, digest);

		// WHEN
		final String result = MAPPER.writeValueAsString(meta);

		// THEN
		final String expectedJson = """
				{"backupPath":"%s","providerKey":"%s","objectKey":"%s","modificationDate":%d,"digest":"%s"}"""
				.formatted(backupPath, providerKey, objectKey, ts, digest);
		then(result).as("JSON generated").isEqualTo(expectedJson);
	}

	@Test
	public void toJson_noDigest() throws IOException {
		// GIVEN
		final String backupPath = randomString();
		final String providerKey = randomString();
		final String objectKey = randomString();
		final long ts = System.currentTimeMillis();
		final S3BackupResourceMetadata meta = new S3BackupResourceMetadata(backupPath, ts, providerKey,
				objectKey, null);

		// WHEN
		final String result = MAPPER.writeValueAsString(meta);

		// THEN
		final String expectedJson = """
				{"backupPath":"%s","providerKey":"%s","objectKey":"%s","modificationDate":%d}"""
				.formatted(backupPath, providerKey, objectKey, ts);
		then(result).as("JSON generated").isEqualTo(expectedJson);
	}

	@Test
	public void fromJson() throws IOException {
		// GIVEN
		final String backupPath = randomString();
		final String providerKey = randomString();
		final String objectKey = randomString();
		final long ts = System.currentTimeMillis();
		final String digest = randomString();
		final String json = """
				{"backupPath":"%s","providerKey":"%s","objectKey":"%s","modificationDate":%d,"digest":"%s"}"""
				.formatted(backupPath, providerKey, objectKey, ts, digest);

		// WHEN
		S3BackupResourceMetadata result = MAPPER.readValue(json, S3BackupResourceMetadata.class);

		// THEN
		// @formatter:off
		then(result)
			.as("JSON parsed")
			.isNotNull()
			.as("Backup path parsed")
			.returns(backupPath, from(S3BackupResourceMetadata::getBackupPath))
			.as("Provider key parsed")
			.returns(providerKey, from(S3BackupResourceMetadata::getProviderKey))
			.as("Object key parsed")
			.returns(objectKey, from(S3BackupResourceMetadata::getObjectKey))
			.as("Modification date parsed")
			.returns(ts, from(S3BackupResourceMetadata::getModificationDate))
			.as("Digest parsed")
			.returns(digest, from(S3BackupResourceMetadata::getDigest))
			;
		// @formatter:on
	}

	@Test
	public void fromJson_noDigest() throws IOException {
		// GIVEN
		final String backupPath = randomString();
		final String providerKey = randomString();
		final String objectKey = randomString();
		final long ts = System.currentTimeMillis();
		final String json = """
				{"backupPath":"%s","providerKey":"%s","objectKey":"%s","modificationDate":%d}"""
				.formatted(backupPath, providerKey, objectKey, ts);

		// WHEN
		S3BackupResourceMetadata result = MAPPER.readValue(json, S3BackupResourceMetadata.class);

		// THEN
		// @formatter:off
		then(result)
			.as("JSON parsed")
			.isNotNull()
			.as("Backup path parsed")
			.returns(backupPath, from(S3BackupResourceMetadata::getBackupPath))
			.as("Provider key parsed")
			.returns(providerKey, from(S3BackupResourceMetadata::getProviderKey))
			.as("Object key parsed")
			.returns(objectKey, from(S3BackupResourceMetadata::getObjectKey))
			.as("Modification date parsed")
			.returns(ts, from(S3BackupResourceMetadata::getModificationDate))
			.as("Digest not available")
			.returns(null, from(S3BackupResourceMetadata::getDigest))
			;
		// @formatter:on
	}

}
