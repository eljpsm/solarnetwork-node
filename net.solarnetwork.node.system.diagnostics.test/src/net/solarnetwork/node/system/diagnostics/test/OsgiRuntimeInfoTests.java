/* ==================================================================
 * OsgiRuntimeInfoTests.java - 16/10/2026 9:00:00 am
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

import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenThrownBy;
import static org.easymock.EasyMock.expect;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;
import net.solarnetwork.node.system.diagnostics.OsgiRuntimeInfo;

/**
 * Test cases for the {@link OsgiRuntimeInfo} class.
 *
 * @author elijah
 * @version 1.0
 */
public class OsgiRuntimeInfoTests {

	private BundleContext bundleContext;
	private List<Object> mocks;

	@Before
	public void setup() {
		mocks = new ArrayList<>(8);
		bundleContext = mock(BundleContext.class);
	}

	@After
	public void teardown() {
		EasyMock.verify(mocks.toArray());
	}

	private <T> T mock(Class<T> clazz) {
		T m = EasyMock.createStrictMock(clazz);
		mocks.add(m);
		return m;
	}

	private void replayAll() {
		EasyMock.replay(mocks.toArray());
	}

	private void expectDbBundle(String name) throws InvalidSyntaxException {
		ServiceReference<?> ref = mock(ServiceReference.class);
		Bundle bundle = mock(Bundle.class);
		expect(bundleContext.getAllServiceReferences(OsgiRuntimeInfo.DATABASE_SYSTEM_SERVICE_CLASS,
				OsgiRuntimeInfo.NODE_DB_FILTER)).andReturn(new ServiceReference<?>[] { ref });
		expect(ref.getBundle()).andReturn(bundle);
		expect(bundle.getSymbolicName()).andReturn(name);
		expect(bundle.getVersion()).andReturn(new Version(1, 2, 3));
	}

	@Test
	public void dbBackend_h2() throws InvalidSyntaxException {
		// GIVEN
		expectDbBundle("net.solarnetwork.node.dao.jdbc.h2");

		// WHEN
		replayAll();
		Map<String, Object> result = OsgiRuntimeInfo.dbBackend(bundleContext);

		// THEN
		// @formatter:off
		then(result)
			.as("H2 backend detected from bundle name")
			.containsOnlyKeys("backend", "bundle", "bundleVersion")
			.containsEntry("backend", "h2")
			.containsEntry("bundle", "net.solarnetwork.node.dao.jdbc.h2")
			.containsEntry("bundleVersion", "1.2.3")
			;
		// @formatter:on
	}

	@Test
	public void dbBackend_postgres() throws InvalidSyntaxException {
		// GIVEN
		expectDbBundle("net.solarnetwork.node.dao.jdbc.postgres");

		// WHEN
		replayAll();
		Map<String, Object> result = OsgiRuntimeInfo.dbBackend(bundleContext);

		// THEN
		then(result).as("Postgres backend detected").containsEntry("backend", "postgres");
	}

	@Test
	public void dbBackend_other() throws InvalidSyntaxException {
		// GIVEN
		expectDbBundle("com.example.db");

		// WHEN
		replayAll();
		Map<String, Object> result = OsgiRuntimeInfo.dbBackend(bundleContext);

		// THEN
		// @formatter:off
		then(result)
			.as("Other backend for unrecognised bundle")
			.containsEntry("backend", "other")
			.containsEntry("bundle", "com.example.db")
			;
		// @formatter:on
	}

	@Test
	public void dbBackend_none() throws InvalidSyntaxException {
		// GIVEN
		expect(bundleContext.getAllServiceReferences(OsgiRuntimeInfo.DATABASE_SYSTEM_SERVICE_CLASS,
				OsgiRuntimeInfo.NODE_DB_FILTER)).andReturn(null);

		// WHEN
		replayAll();
		Map<String, Object> result = OsgiRuntimeInfo.dbBackend(bundleContext);

		// THEN
		then(result).as("Unknown backend when no service").containsOnly(Map.entry("backend", "unknown"));
	}

	@Test
	public void dbBackend_unregistered() throws InvalidSyntaxException {
		// GIVEN
		ServiceReference<?> ref = mock(ServiceReference.class);
		expect(bundleContext.getAllServiceReferences(OsgiRuntimeInfo.DATABASE_SYSTEM_SERVICE_CLASS,
				OsgiRuntimeInfo.NODE_DB_FILTER)).andReturn(new ServiceReference<?>[] { ref });
		expect(ref.getBundle()).andReturn(null);

		// WHEN
		replayAll();
		Map<String, Object> result = OsgiRuntimeInfo.dbBackend(bundleContext);

		// THEN
		then(result).as("Unknown backend when service unregistered")
				.containsOnly(Map.entry("backend", "unknown"));
	}

	@Test
	public void dbBackend_invalidSyntax() throws InvalidSyntaxException {
		// GIVEN
		expect(bundleContext.getAllServiceReferences(OsgiRuntimeInfo.DATABASE_SYSTEM_SERVICE_CLASS,
				OsgiRuntimeInfo.NODE_DB_FILTER)).andThrow(new InvalidSyntaxException("bad", "bad"));

		// WHEN
		replayAll();

		// THEN
		// @formatter:off
		thenThrownBy(() -> OsgiRuntimeInfo.dbBackend(bundleContext))
			.as("Invalid syntax converted to IllegalStateException")
			.isInstanceOf(IllegalStateException.class)
			;
		// @formatter:on
	}

	private Bundle bundle(String name, int state, boolean fragment) {
		Bundle b = mock(Bundle.class);
		expect(b.getState()).andReturn(state);
		if ( state == Bundle.ACTIVE ) {
			return b;
		}
		Dictionary<String, String> headers = new Hashtable<>();
		if ( fragment ) {
			headers.put("Fragment-Host", "net.solarnetwork.node");
		}
		expect(b.getHeaders("")).andReturn(headers);
		if ( fragment ) {
			return b;
		}
		expect(b.getSymbolicName()).andReturn(name);
		expect(b.getVersion()).andReturn(new Version(1, 0, 0));
		return b;
	}

	@Test
	public void bundles() {
		// GIVEN
		Bundle active = bundle("a.active", Bundle.ACTIVE, false);
		Bundle resolved = bundle("b.resolved", Bundle.RESOLVED, false);
		Bundle fragment = bundle("c.fragment", Bundle.RESOLVED, true);
		Bundle installed = bundle("a.installed", Bundle.INSTALLED, false);
		expect(bundleContext.getBundles())
				.andReturn(new Bundle[] { active, resolved, fragment, installed });

		// WHEN
		replayAll();
		Map<String, Object> result = OsgiRuntimeInfo.bundles(bundleContext);

		// THEN
		// @formatter:off
		then(result)
			.as("Total bundle count")
			.containsEntry("total", 4)
			.as("Non-active, non-fragment count")
			.containsEntry("notActiveCount", 2)
			.as("Non-active bundles listed by name, without location")
			.containsEntry("notActive", List.of(
					Map.of("name", "a.installed", "version", "1.0.0", "state", "INSTALLED"),
					Map.of("name", "b.resolved", "version", "1.0.0", "state", "RESOLVED")))
			;
		// @formatter:on
	}

	@Test
	public void bundles_capped() {
		// GIVEN
		Bundle b1 = bundle("c", Bundle.RESOLVED, false);
		Bundle b2 = bundle("a", Bundle.STARTING, false);
		Bundle b3 = bundle("b", Bundle.STOPPING, false);
		expect(bundleContext.getBundles()).andReturn(new Bundle[] { b1, b2, b3 });

		// WHEN
		replayAll();
		Map<String, Object> result = OsgiRuntimeInfo.bundles(bundleContext, 2);

		// THEN
		// @formatter:off
		then(result)
			.as("Count includes all non-active bundles")
			.containsEntry("notActiveCount", 3)
			.as("List capped after sorting")
			.containsEntry("notActive", List.of(
					Map.of("name", "a", "version", "1.0.0", "state", "STARTING"),
					Map.of("name", "b", "version", "1.0.0", "state", "STOPPING")))
			;
		// @formatter:on
	}

}
