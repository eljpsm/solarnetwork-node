/* ==================================================================
 * SystemConfigureIdentityInfo.java - 24/11/2021 3:16:11 PM
 *
 * Copyright 2021 SolarNetwork.net Dev Team
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

package net.solarnetwork.node.setup.impl;

import java.security.cert.X509Certificate;
import org.jspecify.annotations.Nullable;

/**
 * Identity information for {@literal SystemConfigure} instruction support.
 *
 * @author matt
 * @version 1.0
 */
public class SystemConfigureIdentityInfo {

	private final @Nullable Long nodeId;
	private final @Nullable String solarInBaseUrl;
	private final @Nullable X509Certificate nodeCert;

	/**
	 * Constructor.
	 *
	 * @param nodeId
	 *        the node ID, or {@code null} if not known
	 * @param solarInBaseUrl
	 *        the SolarIn base URL, or {@code null} if not known
	 * @param nodeCert
	 *        the node certificate, or {@code null} if not available
	 */
	public SystemConfigureIdentityInfo(@Nullable Long nodeId, @Nullable String solarInBaseUrl,
			@Nullable X509Certificate nodeCert) {
		super();
		this.nodeId = nodeId;
		this.solarInBaseUrl = solarInBaseUrl;
		this.nodeCert = nodeCert;
	}

	/**
	 * Get the node ID.
	 *
	 * @return the nodeId the node ID, or {@code null} if not known
	 */
	public @Nullable Long getNodeId() {
		return nodeId;
	}

	/**
	 * Get the SolarNetwork host name.
	 *
	 * @return the host name, or {@code null} if not known
	 */
	public @Nullable String getSolarInBaseUrl() {
		return solarInBaseUrl;
	}

	/**
	 * Get the node certificate distinguished name (subject).
	 *
	 * @return the node certificate name, or {@code null} if not known
	 */
	public @Nullable String getNodeCertificateDn() {
		return (nodeCert != null ? nodeCert.getSubjectX500Principal().getName() : null);
	}

	/**
	 * Get the node certificate issuer distinguished name (subject).
	 *
	 * @return the node certificate issuer name, or {@code null} if not known
	 */
	public @Nullable String getNodeCertificateIssuerDn() {
		return (nodeCert != null ? nodeCert.getIssuerX500Principal().getName() : null);
	}

	/**
	 * Get the node certificate serial number, as a base-16 encoded string.
	 *
	 * @return the node serial number, or {@code null} if not known
	 */
	public @Nullable String getNodeCertificateSerialNumber() {
		return (nodeCert != null ? String.format("0x%s", nodeCert.getSerialNumber().toString(16))
				: null);
	}

	/**
	 * Get the node certificate valid from date, as an ISO 8601 instant.
	 *
	 * @return the node valid from date, or {@code null} if not known
	 */
	public @Nullable String getNodeCertificateValidFromDate() {
		return (nodeCert != null ? nodeCert.getNotBefore().toInstant().toString() : null);
	}

	/**
	 * Get the node certificate valid to date, as an ISO 8601 instant.
	 *
	 * @return the node valid to date, or {@code null} if not known
	 */
	public @Nullable String getNodeCertificateValidToDate() {
		return (nodeCert != null ? nodeCert.getNotAfter().toInstant().toString() : null);
	}

}
