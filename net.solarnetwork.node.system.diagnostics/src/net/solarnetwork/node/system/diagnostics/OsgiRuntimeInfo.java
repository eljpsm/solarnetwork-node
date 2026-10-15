/* ==================================================================
 * OsgiRuntimeInfo.java - 16/10/2026 9:00:00 am
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

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;

/**
 * Read-only OSGi runtime information helpers.
 *
 * <p>
 * These helpers only inspect service references and bundle metadata. They never
 * obtain a service instance, and never return a bundle location.
 * </p>
 *
 * @author elijah
 * @version 1.0
 */
public final class OsgiRuntimeInfo {

	/** The database system service class name. */
	public static final String DATABASE_SYSTEM_SERVICE_CLASS = "net.solarnetwork.node.dao.jdbc.DatabaseSystemService";

	/** The service filter for the node database. */
	public static final String NODE_DB_FILTER = "(db=node)";

	/** The default maximum number of non-active bundles to list. */
	public static final int DEFAULT_BUNDLE_CAP = 50;

	/** The backend value for an H2 database. */
	public static final String BACKEND_H2 = "h2";

	/** The backend value for a Postgres database. */
	public static final String BACKEND_POSTGRES = "postgres";

	/** The backend value when no database service is available. */
	public static final String BACKEND_UNKNOWN = "unknown";

	/** The backend value for an unrecognised database service. */
	public static final String BACKEND_OTHER = "other";

	private static final String FRAGMENT_HOST_HEADER = "Fragment-Host";

	private OsgiRuntimeInfo() {
		// not available
	}

	/**
	 * Get information about the node database backend.
	 *
	 * <p>
	 * The backend is derived from the symbolic name of the bundle that
	 * registered the node {@code DatabaseSystemService}. The service itself is
	 * never obtained.
	 * </p>
	 *
	 * @param bundleContext
	 *        the bundle context
	 * @return a map with a {@code backend} key and optional {@code bundle} and
	 *         {@code bundleVersion} keys
	 * @throws IllegalStateException
	 *         if the service filter is invalid
	 */
	public static Map<String, Object> dbBackend(BundleContext bundleContext) {
		final ServiceReference<?> @Nullable [] refs;
		try {
			refs = bundleContext.getAllServiceReferences(DATABASE_SYSTEM_SERVICE_CLASS, NODE_DB_FILTER);
		} catch ( InvalidSyntaxException e ) {
			throw new IllegalStateException("Invalid database service filter.", e);
		}
		Map<String, Object> result = new LinkedHashMap<>(4);
		final Bundle bundle = (refs != null && refs.length > 0 && refs[0] != null ? refs[0].getBundle()
				: null);
		final String name = (bundle != null ? bundle.getSymbolicName() : null);
		if ( bundle == null || name == null ) {
			result.put("backend", BACKEND_UNKNOWN);
			return result;
		}
		final String backend;
		if ( name.endsWith(".h2") ) {
			backend = BACKEND_H2;
		} else if ( name.endsWith(".postgres") ) {
			backend = BACKEND_POSTGRES;
		} else {
			backend = BACKEND_OTHER;
		}
		result.put("backend", backend);
		result.put("bundle", name);
		Version v = bundle.getVersion();
		if ( v != null ) {
			result.put("bundleVersion", v.toString());
		}
		return result;
	}

	/**
	 * Get information about bundles that are not active, using
	 * {@link #DEFAULT_BUNDLE_CAP}.
	 *
	 * @param bundleContext
	 *        the bundle context
	 * @return the bundle information
	 * @see #bundles(BundleContext, int)
	 */
	public static Map<String, Object> bundles(BundleContext bundleContext) {
		return bundles(bundleContext, DEFAULT_BUNDLE_CAP);
	}

	/**
	 * Get information about bundles that are not active.
	 *
	 * <p>
	 * Fragment bundles are never active, so they are ignored.
	 * </p>
	 *
	 * @param bundleContext
	 *        the bundle context
	 * @param cap
	 *        the maximum number of non-active bundles to list
	 * @return a map with {@code total} (the total bundle count),
	 *         {@code notActiveCount}, and {@code notActive} (a list of
	 *         {@code name}, {@code version}, {@code state} maps sorted by name)
	 *         keys
	 */
	public static Map<String, Object> bundles(BundleContext bundleContext, int cap) {
		final Bundle[] bundles = bundleContext.getBundles();
		final List<Map<String, Object>> notActive = new ArrayList<>(8);
		int total = 0;
		if ( bundles != null ) {
			for ( Bundle b : bundles ) {
				if ( b == null ) {
					continue;
				}
				total++;
				final int state = b.getState();
				if ( state == Bundle.ACTIVE || isFragment(b) ) {
					continue;
				}
				Map<String, Object> m = new LinkedHashMap<>(4);
				String name = b.getSymbolicName();
				m.put("name", name != null ? name : "");
				Version v = b.getVersion();
				if ( v != null ) {
					m.put("version", v.toString());
				}
				m.put("state", stateName(state));
				notActive.add(m);
			}
		}
		notActive.sort((l, r) -> {
			int c = String.valueOf(l.get("name")).compareTo(String.valueOf(r.get("name")));
			if ( c == 0 ) {
				c = String.valueOf(l.get("version")).compareTo(String.valueOf(r.get("version")));
			}
			return c;
		});
		Map<String, Object> result = new LinkedHashMap<>(4);
		result.put("total", total);
		result.put("notActiveCount", notActive.size());
		result.put("notActive",
				notActive.size() > cap ? new ArrayList<>(notActive.subList(0, Math.max(0, cap)))
						: notActive);
		return result;
	}

	private static boolean isFragment(Bundle b) {
		Dictionary<String, String> headers = b.getHeaders("");
		return (headers != null && headers.get(FRAGMENT_HOST_HEADER) != null);
	}

	/**
	 * Get a bundle state name.
	 *
	 * @param state
	 *        the bundle state
	 * @return the name
	 */
	public static String stateName(int state) {
		switch (state) {
			case Bundle.INSTALLED:
				return "INSTALLED";
			case Bundle.RESOLVED:
				return "RESOLVED";
			case Bundle.STARTING:
				return "STARTING";
			case Bundle.STOPPING:
				return "STOPPING";
			case Bundle.ACTIVE:
				return "ACTIVE";
			case Bundle.UNINSTALLED:
				return "UNINSTALLED";
			default:
				return "UNKNOWN";
		}
	}

}
