/* ==================================================================
 * S3BackupResourceMetadata.java - 3/10/2017 7:53:06 PM
 *
 * Copyright 2017 SolarNetwork.net Dev Team
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

package net.solarnetwork.node.backup.s3;

import static net.solarnetwork.util.ObjectUtils.requireNonNullArgument;
import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Metadata on a single backup resource within a backup.
 *
 * @author matt
 * @version 1.1
 */
@JsonPropertyOrder({ "backupPath", "providerKey", "objectKey", "modificationDate", "digest" })
public class S3BackupResourceMetadata {

	private final String backupPath;
	private final long modificationDate;
	private final String providerKey;
	private final String objectKey;
	private final @Nullable String digest;

	/**
	 * Constructor.
	 *
	 * @param backupPath
	 *        the backup path
	 * @param modificationDate
	 *        the modification date
	 * @param providerKey
	 *        the provider key
	 * @param objectKey
	 *        the object key
	 * @param digest
	 *        the optional content digest
	 * @throws IllegalArgumentException
	 *         if any argument except {@code digest} is {@code null}
	 */
	@JsonCreator
	public S3BackupResourceMetadata(
	// @formatter:off
			@JsonProperty("backupPath") String backupPath,
			@JsonProperty("modificationDate") long modificationDate,
			@JsonProperty("providerKey") String providerKey,
			@JsonProperty("objectKey") String objectKey,
			@JsonProperty(value = "digest", required = false) @Nullable String digest
			// @formatter:on
	) {
		super();
		this.backupPath = requireNonNullArgument(backupPath, "backupPath");
		this.modificationDate = modificationDate;
		this.providerKey = requireNonNullArgument(providerKey, "providerKey");
		this.objectKey = requireNonNullArgument(objectKey, "objectKey");
		this.digest = digest;
	}

	/**
	 * Get the backup path.
	 *
	 * @return the backup path
	 */
	public final String getBackupPath() {
		return backupPath;
	}

	/**
	 * Get the modification date.
	 *
	 * @return the modification date
	 */
	public final long getModificationDate() {
		return modificationDate;
	}

	/**
	 * Get the provider key.
	 *
	 * @return the provider key
	 */
	public final String getProviderKey() {
		return providerKey;
	}

	/**
	 * Get the object key.
	 *
	 * @return the object key
	 */
	public final String getObjectKey() {
		return objectKey;
	}

	/**
	 * Get the digest.
	 *
	 * @return the digest
	 */
	public final @Nullable String getDigest() {
		return digest;
	}

}
