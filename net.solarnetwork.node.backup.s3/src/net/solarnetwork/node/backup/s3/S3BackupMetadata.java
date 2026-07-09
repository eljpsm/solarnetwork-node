/* ==================================================================
 * S3BackupMetadata.java - 3/10/2017 5:53:20 PM
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

import static java.util.Objects.requireNonNullElse;
import static net.solarnetwork.util.ObjectUtils.requireNonNullArgument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import net.solarnetwork.common.s3.S3ObjectReference;
import net.solarnetwork.node.backup.Backup;
import net.solarnetwork.node.backup.BackupIdentity;
import net.solarnetwork.node.backup.BackupResource;

/**
 * S3 implementation of {@link Backup}.
 *
 * @author matt
 * @version 1.2
 */
@JsonPropertyOrder({ "key", "nodeId", "date", "qualifier", "size", "complete" })
@JsonIgnoreProperties({ "id" })
public class S3BackupMetadata implements Backup {

	private @Nullable Long nodeId;
	private String key;
	private Date date;
	private @Nullable String qualifier;
	private boolean complete;

	private @Nullable List<S3BackupResourceMetadata> resourceMetadata;

	/**
	 * Construct with a key and date.
	 *
	 * @param key
	 *        the key
	 * @param date
	 *        the date
	 * @throws IllegalArgumentException
	 *         if any argument is {@code null}
	 */
	@JsonCreator
	public S3BackupMetadata(@JsonProperty("key") String key, @JsonProperty("date") Date date) {
		this.key = requireNonNullArgument(key, "key");
		this.date = requireNonNullArgument(date, "date");
	}

	/**
	 * Constructor.
	 *
	 * @param objRef
	 *        the object reference
	 * @throws IllegalArgumentException
	 *         if any argument is {@code null}
	 */
	public S3BackupMetadata(S3ObjectReference objRef) {
		super();
		objRef = requireNonNullArgument(objRef, "objRef");
		this.date = requireNonNullElse(objRef.getModified(), Date.from(Instant.EPOCH));
		this.key = objRef.getKey();
		this.complete = true;
		extractKeyComponents(this.key);
	}

	private void extractKeyComponents(String key) {
		BackupIdentity ident = S3BackupService.identityFromBackupKey(key);
		if ( ident != null ) {
			if ( this.nodeId == null ) {
				setNodeId(ident.getNodeId());
			}
			if ( this.date == null ) {
				setDate(ident.getDate());
			}
			if ( this.qualifier == null ) {
				setQualifier(ident.getQualifier());
			}
		}
	}

	@Override
	public String getKey() {
		return key;
	}

	/**
	 * Set the backup key.
	 *
	 * @param key
	 *        the key to set
	 * @throws IllegalArgumentException
	 *         if any argument is {@code null}
	 */
	public void setKey(String key) {
		this.key = requireNonNullArgument(key, "key");
		extractKeyComponents(this.key);
	}

	@Override
	public boolean isComplete() {
		return complete;
	}

	@Override
	public Date getDate() {
		return date;
	}

	/**
	 * Set the backup date.
	 *
	 * @param date
	 *        the date
	 */
	public void setDate(Date date) {
		this.date = date;
	}

	@Override
	public @Nullable Long getSize() {
		return null;
	}

	@Override
	public @Nullable Long getNodeId() {
		return nodeId;
	}

	/**
	 * Set the node ID.
	 *
	 * @param nodeId
	 *        the node ID to set
	 */
	public void setNodeId(@Nullable Long nodeId) {
		this.nodeId = nodeId;
	}

	/**
	 * Set the completed flag.
	 *
	 * @param complete
	 *        {@code true} if completed
	 */
	public void setComplete(boolean complete) {
		this.complete = complete;
	}

	/**
	 * Add a resource to the resource list.
	 *
	 * @param resource
	 *        the resource to add
	 * @param objectKey
	 *        the S3 object key
	 * @param digest
	 *        the digest of the resource contents, if known
	 */
	public void addBackupResource(BackupResource resource, String objectKey, @Nullable String digest) {
		var meta = new S3BackupResourceMetadata(resource.getBackupPath(), resource.getModificationDate(),
				resource.getProviderKey(), objectKey, digest);
		if ( resourceMetadata == null ) {
			resourceMetadata = new ArrayList<>(16);
		}
		resourceMetadata.add(meta);
	}

	/**
	 * Get the resource metadata.
	 *
	 * @return the metadata
	 */
	public @Nullable List<S3BackupResourceMetadata> getResourceMetadata() {
		return resourceMetadata;
	}

	/**
	 * Set the resource metadata.
	 *
	 * @param resourceMetadata
	 *        the metadata to set
	 */
	public void setResourceMetadata(@Nullable List<S3BackupResourceMetadata> resourceMetadata) {
		this.resourceMetadata = resourceMetadata;
	}

	@Override
	public @Nullable String getQualifier() {
		return qualifier;
	}

	/**
	 * Set the qualifier.
	 *
	 * @param qualifier
	 *        the qualifier to set
	 */
	public void setQualifier(@Nullable String qualifier) {
		this.qualifier = qualifier;
	}

}
